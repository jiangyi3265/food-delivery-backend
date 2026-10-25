package com.ruoyi.web.controller.food;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FoodService {
    private final JdbcTemplate db;
    private final ObjectMapper json = new ObjectMapper();
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final Map<String, long[]> attempts = new ConcurrentHashMap<>();
    @Value("${food.seed-password:}") private String seedPassword;
    public FoodService(JdbcTemplate db) { this.db = db; }
    public static Map<String,Object> map(Object... values) { Map<String,Object> m=new LinkedHashMap<>(); for(int i=0;i<values.length;i+=2)m.put(values[i].toString(),values[i+1]); return m; }
    public static String str(Map<String,Object> m,String k) { return m.get(k)==null?"":m.get(k).toString(); }
    public static long id(Object v) { try {return Long.parseLong(String.valueOf(v));}catch(Exception e){throw error(400,"参数格式不正确");} }
    public static BigDecimal money(Object v) { try { BigDecimal n=new BigDecimal(String.valueOf(v)); if(n.scale()>2 || n.abs().compareTo(new BigDecimal("99999999"))>0)throw new Exception(); return n.setScale(2); }catch(Exception e){throw error(400,"金额或数量最多两位小数，且应在有效范围内");} }
    public static Failure error(int status,String message){return new Failure(status,message);}
    public static class Failure extends RuntimeException { public final int status; public Failure(int s,String m){super(m);status=s;} }
    private String required(Map<String,Object> b,String key,int limit){String s=str(b,key).trim();if(s.isEmpty()||s.length()>limit)throw error(400,"请填写有效的"+key);return s;}
    private String encode(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException(e);}}
    private List<Map<String,Object>> decode(String v){try{return json.readValue(v,new TypeReference<List<Map<String,Object>>>(){});}catch(Exception e){throw new IllegalStateException(e);}}
    private Map<String,Object> one(String sql,Object... a){List<Map<String,Object>> x=db.queryForList(sql,a);if(x.isEmpty())throw error(404,"记录不存在");return x.get(0);}
    private void role(Map<String,Object> u,String... roles){if(!Arrays.asList(roles).contains(str(u,"role")))throw error(403,"当前账号无此操作权限");}
    private String hash(String token){try{byte[] d=MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:d)s.append(String.format("%02x",b));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    private String token(){byte[] b=new byte[32];new SecureRandom().nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}

    @PostConstruct public void init(){
        new ResourceDatabasePopulator(new ClassPathResource("food-schema.sql")).execute(Objects.requireNonNull(db.getDataSource()));
        repairLegacySeedAddresses();
        if(db.queryForObject("SELECT count(*) FROM food_account",Integer.class)>0)return;
        if(seedPassword == null || seedPassword.length() < 8 || seedPassword.length() > 72)throw new IllegalStateException("Set FOOD_SEED_PASSWORD (8-72 characters) before initializing demo accounts");
        String pw=passwords.encode(seedPassword);
        db.update("INSERT INTO food_supplier(id,name,contact,phone) VALUES (1,'绿源食材','李经理','13800006688'),(2,'丰禾配送','王经理','13900001122'),(3,'晨鲜粮油','杨经理','13700007766')");
        db.update("INSERT INTO food_store(id,name,contact,phone,address,supplier_id) VALUES(1,'A食堂','张师傅','13800002688','北京市海淀区学院路88号 · A食堂后厨收货处',1),(2,'B食堂','李师傅','13900008866','北京市海淀区学院路126号 · B食堂后厨',1),(3,'C食堂','王师傅','13600008221','北京市海淀区学清路16号 · C食堂收货处',2)");
        String[] names={"土豆","新鲜西红柿","上海青","鲜鸡蛋","精品五花肉","胡萝卜"};String[] prices={"2.50","4.50","3.20","6.80","18.50","2.80"};
        for(int i=0;i<names.length;i++)db.update("INSERT INTO food_product(name,category,unit,spec,price,minimum,image,description) VALUES(?,?,?,?,?,?,?,?)",names[i],i==3||i==4?"肉禽":"蔬菜","斤","新鲜散装",prices[i],i==1?5:1,String.valueOf(i),"当日鲜采，严格甄选。适合食堂日常备餐，按需采购，按时送达。");
        db.update("INSERT INTO food_account(username,password_hash,name,role) VALUES('admin',?,'平台管理员','admin')",pw);
        db.update("INSERT INTO food_account(username,password_hash,name,role,store_id) VALUES('store_a',?,'A食堂采购员','store',1),('store_b',?,'B食堂采购员','store',2),('store_c',?,'C食堂采购员','store',3)",pw,pw,pw);
        db.update("INSERT INTO food_account(username,password_hash,name,role,supplier_id) VALUES('supplier_a',?,'绿源食材','supplier',1),('supplier_b',?,'丰禾配送','supplier',2)",pw,pw);
        List<Map<String,Object>> items=new ArrayList<>();int[] qty={20,10,10};
        for(int i=0;i<3;i++)items.add(map("productId",i+1,"name",names[i],"unit","斤","spec","新鲜散装","image",String.valueOf(i),"quantity",qty[i],"price",prices[i],"amount",money(prices[i]).multiply(new BigDecimal(qty[i])),"picked",true));
        String[] states={"pending","preparing","ready","delivering","delivered","completed","completed","completed","pending"};
        for(int i=0;i<states.length;i++){
            int store=i==8?3:i%3==1?2:1,supplier=store==3?2:1;
            Map<String,Object> recipient=one("SELECT contact,phone,address FROM food_store WHERE id=?",store);
            db.update("INSERT INTO food_order(order_no,request_key,account_id,store_id,supplier_id,status,items_json,total,delivery_date,delivery_slot,contact,phone,address,remark,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,DATE_SUB(NOW(),INTERVAL ? DAY),NOW())","SC"+LocalDate.now().toString().replace("-","")+String.format("%04d",i+1),"seed-"+i,store+1,store,supplier,states[i],encode(items),127,LocalDate.now().plusDays(i<3?1:0).toString(),"06:00—08:00",recipient.get("contact"),recipient.get("phone"),recipient.get("address"),"请在后厨入口交货",i);
        }
    }

    private void repairLegacySeedAddresses(){
        // Match only the original demo fixtures; preserve real order address snapshots.
        db.update("UPDATE food_order o JOIN food_store s ON s.id=o.store_id SET o.contact=s.contact,o.phone=s.phone,o.address=s.address "
            + "WHERE o.request_key REGEXP '^seed-[0-8]$' AND o.order_no REGEXP '^SC[0-9]{12}$' "
            + "AND o.remark='请在后厨入口交货' AND o.contact='张师傅' AND o.phone='13800002688' AND o.address='A食堂 · 后厨收货处'");
    }

    public Map<String,Object> login(Map<String,Object> b){
        String username=required(b,"username",60);String password=required(b,"password",100);
        long now=System.currentTimeMillis();long[] rate=attempts.computeIfAbsent(username,k->new long[]{now,0});
        synchronized(rate){if(now-rate[0]>900000){rate[0]=now;rate[1]=0;}if(rate[1]>=8)throw error(429,"登录尝试过多，请15分钟后重试");rate[1]++;}
        List<Map<String,Object>> found=db.queryForList("SELECT * FROM food_account WHERE username=?",username);
        if(found.isEmpty()||!passwords.matches(password,str(found.get(0),"password_hash")))throw error(401,"账号或密码不正确");
        Map<String,Object> u=found.get(0);if(id(u.get("active"))!=1)throw error(403,"账号已停用");checkEntity(u);
        attempts.remove(username);String token=token();db.update("INSERT INTO food_session(token_hash,account_id,expires_at) VALUES(?,?,DATE_ADD(NOW(),INTERVAL 8 HOUR))",hash(token),u.get("id"));
        return map("token",token,"user",publicUser(u));
    }
    public Map<String,Object> user(String authorization){
        if(authorization==null||!authorization.startsWith("Bearer "))throw error(401,"请先登录");
        List<Map<String,Object>> rows=db.queryForList("SELECT a.* FROM food_session s JOIN food_account a ON s.account_id=a.id WHERE s.token_hash=? AND s.expires_at>NOW() AND a.active=1",hash(authorization.substring(7)));
        if(rows.isEmpty())throw error(401,"登录已过期，请重新登录");checkEntity(rows.get(0));return rows.get(0);
    }
    private void checkEntity(Map<String,Object> u){String role=str(u,"role");if("store".equals(role)||"supplier".equals(role)){String table="store".equals(role)?"food_store":"food_supplier";Object entity=u.get(role+"_id");if(entity==null||db.queryForObject("SELECT count(*) FROM "+table+" WHERE id=? AND active=1",Integer.class,entity)!=1)throw error(403,"所属门店或供应商已停用");}}
    public void logout(String authorization){db.update("DELETE FROM food_session WHERE token_hash=?",hash(authorization.substring(7)));}
    private Map<String,Object> publicUser(Map<String,Object> u){return map("id",u.get("id"),"username",u.get("username"),"name",u.get("name"),"role",u.get("role"),"storeId",u.get("store_id"),"supplierId",u.get("supplier_id"));}
    private String orderScope(Map<String,Object> u){return "store".equals(str(u,"role"))?" AND o.store_id="+id(u.get("store_id")):"supplier".equals(str(u,"role"))?" AND o.supplier_id="+id(u.get("supplier_id")):"";}
    public List<Map<String,Object>> orders(Map<String,Object> u){
        List<Map<String,Object>> list=db.queryForList("SELECT o.*,s.name storeName,p.name supplierName,p.phone supplierPhone,COALESCE((SELECT SUM(a.amount) FROM food_allocation a WHERE a.order_id=o.id),0) paid FROM food_order o JOIN food_store s ON s.id=o.store_id JOIN food_supplier p ON p.id=o.supplier_id WHERE 1=1"+orderScope(u)+" ORDER BY o.id DESC");
        for(Map<String,Object> o:list){List<Map<String,Object>> items=decode(str(o,"items_json"));o.remove("items_json");o.put("items",items);o.put("events",db.queryForList("SELECT action,note,created_at FROM food_order_event WHERE order_id=? ORDER BY id",o.get("id")));if("supplier".equals(str(u,"role"))){o.remove("total");o.remove("paid");for(Map<String,Object> item:items){item.remove("price");item.remove("amount");}}}
        return list;
    }
    public Map<String,Object> bootstrap(Map<String,Object> u){
        String role=str(u,"role");String ss="store".equals(role)?" WHERE s.id="+id(u.get("store_id")):"supplier".equals(role)?" WHERE s.supplier_id="+id(u.get("supplier_id")):"";
        List<Map<String,Object>> stores=db.queryForList("SELECT s.*,p.name supplierName,p.phone supplierPhone FROM food_store s LEFT JOIN food_supplier p ON p.id=s.supplier_id"+ss);
        String ps="supplier".equals(role)?" WHERE id="+id(u.get("supplier_id")):"store".equals(role)?" WHERE id=(SELECT supplier_id FROM food_store WHERE id="+id(u.get("store_id"))+")":"";
        List<Map<String,Object>> suppliers=db.queryForList("SELECT * FROM food_supplier"+ps);
        List<Map<String,Object>> products="supplier".equals(role)?Collections.emptyList():db.queryForList("SELECT * FROM food_product"+("store".equals(role)?" WHERE active=1":"")+" ORDER BY id");
        List<Map<String,Object>> settlements="supplier".equals(role)?Collections.emptyList():db.queryForList("SELECT t.*,s.name storeName FROM food_settlement t JOIN food_store s ON s.id=t.store_id"+("store".equals(role)?" WHERE t.store_id="+id(u.get("store_id")):"")+" ORDER BY t.id DESC");
        return map("user",publicUser(u),"products",products,"stores",stores,"suppliers",suppliers,"orders",orders(u),"settlements",settlements,"cart",db.queryForList("SELECT product_id productId,quantity FROM food_cart WHERE account_id=?",u.get("id")),"accounts","admin".equals(role)?db.queryForList("SELECT id,username,name,role,store_id storeId,supplier_id supplierId,active FROM food_account ORDER BY id"):Collections.emptyList(),"feedback","admin".equals(role)?db.queryForList("SELECT f.*,a.name accountName FROM food_feedback f JOIN food_account a ON a.id=f.account_id ORDER BY f.id DESC"):Collections.emptyList());
    }
    @Transactional public void cart(Map<String,Object> u,Map<String,Object> b){role(u,"store");long p=id(b.get("productId"));BigDecimal q=money(b.get("quantity"));if(q.signum()<0||q.compareTo(new BigDecimal("100000"))>0)throw error(400,"数量超出范围");if(q.signum()==0){db.update("DELETE FROM food_cart WHERE account_id=? AND product_id=?",u.get("id"),p);return;}Map<String,Object> product=one("SELECT * FROM food_product WHERE id=? AND active=1",p);if(q.compareTo(money(product.get("minimum")))<0)throw error(400,"数量不能小于起订量");db.update("INSERT INTO food_cart(account_id,product_id,quantity) VALUES(?,?,?) ON DUPLICATE KEY UPDATE quantity=VALUES(quantity)",u.get("id"),p,q);}

    @Transactional public Map<String,Object> createOrder(Map<String,Object> u,Map<String,Object> b){
        role(u,"store");String key=required(b,"requestKey",100);
        Map<String,Object> store=one("SELECT * FROM food_store WHERE id=? FOR UPDATE",u.get("store_id"));
        if(id(store.get("active"))!=1)throw error(403,"门店已停用，不能下单");
        List<Map<String,Object>> old=db.queryForList("SELECT id FROM food_order WHERE account_id=? AND request_key=?",u.get("id"),key);if(!old.isEmpty())return old.get(0);
        if(store.get("supplier_id")==null)throw error(409,"门店尚未绑定供应商，请联系平台");one("SELECT id FROM food_supplier WHERE id=? AND active=1",store.get("supplier_id"));
        LocalDate date;try{date=LocalDate.parse(required(b,"deliveryDate",10));}catch(Exception e){throw error(400,"请选择有效配送日期");}if(date.isBefore(LocalDate.now())||date.isAfter(LocalDate.now().plusDays(30)))throw error(400,"配送日期应在今天至30天内");
        String slot=required(b,"deliverySlot",50);if(!Arrays.asList("06:00—08:00","08:00—10:00","14:00—16:00").contains(slot))throw error(400,"请选择配送时段");
        Object raw=b.get("items");if(!(raw instanceof List)||((List<?>)raw).isEmpty()||((List<?>)raw).size()>100)throw error(400,"请选择食材");
        List<Map<String,Object>> items=new ArrayList<>();Set<Long> seen=new HashSet<>();BigDecimal total=BigDecimal.ZERO;
        for(Object row:(List<?>)raw){if(!(row instanceof Map))throw error(400,"食材数据不正确");Map<String,Object> r=(Map<String,Object>)row;long pid=id(r.get("productId"));if(!seen.add(pid))throw error(400,"食材不能重复");Map<String,Object> p=one("SELECT * FROM food_product WHERE id=? AND active=1 FOR UPDATE",pid);BigDecimal q=money(r.get("quantity"));if(q.compareTo(money(p.get("minimum")))<0||q.compareTo(new BigDecimal("100000"))>0)throw error(400,str(p,"name")+"数量不符合起订要求");BigDecimal amount=money(p.get("price")).multiply(q).setScale(2,RoundingMode.HALF_UP);total=total.add(amount);items.add(map("productId",pid,"name",p.get("name"),"spec",p.get("spec"),"unit",p.get("unit"),"image",p.get("image"),"price",p.get("price"),"quantity",q,"amount",amount,"picked",false));}
        if(total.compareTo(new BigDecimal("999999999999.99"))>0)throw error(400,"订单金额超出范围");
        String no="SC"+LocalDate.now().toString().replace("-","")+UUID.randomUUID().toString().substring(0,8).toUpperCase();String remark=str(b,"remark");if(remark.length()>500)throw error(400,"备注最多500字");
        db.update("INSERT INTO food_order(order_no,request_key,account_id,store_id,supplier_id,status,items_json,total,delivery_date,delivery_slot,contact,phone,address,remark,created_at,updated_at) VALUES(?,?,?,?,?,'pending',?,?,?,?,?,?,?,?,NOW(),NOW())",no,key,u.get("id"),store.get("id"),store.get("supplier_id"),encode(items),total,date.toString(),slot,store.get("contact"),store.get("phone"),store.get("address"),remark);
        Map<String,Object> o=one("SELECT id,order_no FROM food_order WHERE order_no=?",no);event(o,u,"submit","门店提交订单，系统自动分配供应商");for(Long pid:seen)db.update("DELETE FROM food_cart WHERE account_id=? AND product_id=?",u.get("id"),pid);return o;
    }
    private Map<String,Object> scopedOrder(Map<String,Object> u,long oid){List<Map<String,Object>> list=db.queryForList("SELECT o.* FROM food_order o WHERE o.id=?"+orderScope(u)+" FOR UPDATE",oid);if(list.isEmpty())throw error(404,"订单不存在或无权访问");return list.get(0);}
    private void event(Map<String,Object> o,Map<String,Object> u,String action,String note){db.update("INSERT INTO food_order_event(order_id,actor_id,action,note,created_at) VALUES(?,?,?,?,NOW())",o.get("id"),u.get("id"),action,note);}
    @Transactional public void action(Map<String,Object> u,long oid,Map<String,Object> b){
        Map<String,Object> o=scopedOrder(u,oid);String action=required(b,"action",30),status=str(o,"status"),next=null;String note=str(b,"note");
        switch(action){
            case "accept":role(u,"supplier");if(status.equals("pending"))next="preparing";break;
            case "packed":role(u,"supplier");if(status.equals("preparing")){for(Map<String,Object> i:decode(str(o,"items_json")))if(!Boolean.TRUE.equals(i.get("picked")))throw error(409,"请逐项确认食材已备齐");next="ready";}break;
            case "start":role(u,"supplier");if(status.equals("ready"))next="delivering";break;
            case "deliver":role(u,"supplier");if(status.equals("delivering"))next="delivered";break;
            case "receive":role(u,"store");if(status.equals("delivered"))next="completed";break;
            case "cancel":role(u,"store","admin");if(status.equals("pending"))next="cancelled";break;
            case "report":role(u,"store","supplier","admin");if(Arrays.asList("pending","preparing","ready","delivering","delivered").contains(status)){note=required(b,"note",500);db.update("UPDATE food_order SET previous_status=?,issue=? WHERE id=?",status,note,oid);next="issue";}break;
            case "resolve":role(u,"admin");if(status.equals("issue")){note=required(b,"note",500);next=str(o,"previous_status");}break;
            default:throw error(400,"不支持的订单操作");
        }
        if(next==null)throw error(409,"订单状态已变化，请刷新后重试");db.update("UPDATE food_order SET status=?,updated_at=NOW() WHERE id=?",next,oid);event(o,u,action,note.isEmpty()?next:note);
    }
    @Transactional public void picking(Map<String,Object> u,long oid,Map<String,Object> b){role(u,"supplier");Map<String,Object> o=scopedOrder(u,oid);if(!str(o,"status").equals("preparing"))throw error(409,"仅待备货订单可修改备货清单");long pid=id(b.get("productId"));List<Map<String,Object>> items=decode(str(o,"items_json"));boolean found=false;for(Map<String,Object> i:items)if(id(i.get("productId"))==pid){i.put("picked",Boolean.TRUE.equals(b.get("picked")));found=true;}if(!found)throw error(404,"订单食材不存在");db.update("UPDATE food_order SET items_json=?,updated_at=NOW() WHERE id=?",encode(items),oid);}

    @Transactional public Map<String,Object> saveProduct(Map<String,Object> u,Map<String,Object> b){role(u,"admin");String name=required(b,"name",80),category=required(b,"category",40),unit=required(b,"unit",15),spec=required(b,"spec",80),image=required(b,"image",500);BigDecimal price=money(b.get("price")),minimum=money(b.get("minimum"));if(price.signum()<=0||minimum.signum()<=0)throw error(400,"单价和起订量应大于0");if(!image.matches("[0-5]|/profile/food/[a-zA-Z0-9._-]+"))throw error(400,"请使用食材图库或上传图片");String description=str(b,"description");if(description.length()>500)throw error(400,"描述最多500字");int active=Boolean.FALSE.equals(b.get("active"))||"0".equals(str(b,"active"))?0:1;
        if(b.get("id")==null){db.update("INSERT INTO food_product(name,category,unit,spec,price,minimum,image,description,active) VALUES(?,?,?,?,?,?,?,?,?)",name,category,unit,spec,price,minimum,image,description,active);}else{one("SELECT id FROM food_product WHERE id=?",b.get("id"));db.update("UPDATE food_product SET name=?,category=?,unit=?,spec=?,price=?,minimum=?,image=?,description=?,active=? WHERE id=?",name,category,unit,spec,price,minimum,image,description,active,b.get("id"));}return map("saved",true);
    }
    @Transactional public void saveEntity(Map<String,Object> u,String type,Map<String,Object> b){role(u,"admin");String table=type.equals("stores")?"food_store":type.equals("suppliers")?"food_supplier":null;if(table==null)throw error(400,"类型不支持");String name=required(b,"name",80),contact=required(b,"contact",50),phone=required(b,"phone",30);if(!phone.matches("[0-9+() -]{6,30}"))throw error(400,"联系电话格式不正确");int active=Boolean.FALSE.equals(b.get("active"))||"0".equals(str(b,"active"))?0:1;boolean store=type.equals("stores");String address=store?required(b,"address",300):"";
        if(b.get("id")==null){if(store)db.update("INSERT INTO food_store(name,contact,phone,address,active) VALUES(?,?,?,?,?)",name,contact,phone,address,active);else db.update("INSERT INTO food_supplier(name,contact,phone,active) VALUES(?,?,?,?)",name,contact,phone,active);}else{long eid=id(b.get("id"));one("SELECT id FROM "+table+" WHERE id=? FOR UPDATE",eid);if(active==0){String column=store?"store_id":"supplier_id";if(db.queryForObject("SELECT count(*) FROM food_order WHERE "+column+"=? AND status NOT IN ('completed','cancelled')",Integer.class,eid)>0)throw error(409,"存在未完成订单，暂不能停用");if(!store&&db.queryForObject("SELECT count(*) FROM food_store WHERE supplier_id=? AND active=1",Integer.class,eid)>0)throw error(409,"请先调整负责门店的配送关系");}if(store)db.update("UPDATE food_store SET name=?,contact=?,phone=?,address=?,active=? WHERE id=?",name,contact,phone,address,active,eid);else db.update("UPDATE food_supplier SET name=?,contact=?,phone=?,active=? WHERE id=?",name,contact,phone,active,eid);}}
    @Transactional public void binding(Map<String,Object> u,Map<String,Object> b){role(u,"admin");long sid=id(b.get("storeId")),pid=id(b.get("supplierId"));one("SELECT id FROM food_store WHERE id=? FOR UPDATE",sid);one("SELECT id FROM food_supplier WHERE id=? AND active=1",pid);db.update("UPDATE food_store SET supplier_id=? WHERE id=?",pid,sid);}
    @Transactional public void settlement(Map<String,Object> u,Map<String,Object> b){
        role(u,"admin");String key=required(b,"requestKey",100);long sid=id(b.get("storeId"));one("SELECT id FROM food_store WHERE id=? FOR UPDATE",sid);if(db.queryForObject("SELECT count(*) FROM food_settlement WHERE request_key=?",Integer.class,key)>0)return;
        BigDecimal amount=money(b.get("amount"));if(amount.signum()<=0)throw error(400,"结算金额必须大于0");LocalDate date;try{date=LocalDate.parse(str(b,"date"));}catch(Exception e){throw error(400,"请选择结算日期");}if(date.isAfter(LocalDate.now()))throw error(400,"不能登记未来收款");
        List<Map<String,Object>> orders=db.queryForList("SELECT o.id,o.total-COALESCE((SELECT SUM(a.amount) FROM food_allocation a WHERE a.order_id=o.id),0) unpaid FROM food_order o WHERE o.store_id=? AND o.status='completed' ORDER BY o.id FOR UPDATE",sid);BigDecimal available=BigDecimal.ZERO;for(Map<String,Object> o:orders)available=available.add(money(o.get("unpaid")));if(amount.compareTo(available)>0)throw error(409,"金额超出已完成订单的待结算余额 ¥"+available);
        String note=required(b,"note",500);db.update("INSERT INTO food_settlement(store_id,amount,settlement_date,note,request_key,actor_id,created_at) VALUES(?,?,?,?,?,?,NOW())",sid,amount,date.toString(),note,key,u.get("id"));long settlementId=id(one("SELECT id FROM food_settlement WHERE request_key=?",key).get("id"));BigDecimal rest=amount;for(Map<String,Object> o:orders){BigDecimal part=rest.min(money(o.get("unpaid")));if(part.signum()>0){db.update("INSERT INTO food_allocation(settlement_id,order_id,amount) VALUES(?,?,?)",settlementId,o.get("id"),part);rest=rest.subtract(part);}if(rest.signum()==0)break;}
    }
    @Transactional public void account(Map<String,Object> u,Map<String,Object> b){role(u,"admin");String username=required(b,"username",60),name=required(b,"name",80),r=required(b,"role",20);if(!username.matches("[a-zA-Z0-9_]{3,60}")||!Arrays.asList("store","supplier","admin").contains(r))throw error(400,"账号格式或角色不正确");Object store=null,supplier=null;if(r.equals("store")){store=id(b.get("storeId"));one("SELECT id FROM food_store WHERE id=? AND active=1",store);}if(r.equals("supplier")){supplier=id(b.get("supplierId"));one("SELECT id FROM food_supplier WHERE id=? AND active=1",supplier);}int active=Boolean.FALSE.equals(b.get("active"))||"0".equals(str(b,"active"))?0:1;String pw=str(b,"password");if((b.get("id")==null||!pw.isEmpty())&&pw.length()<8)throw error(400,"密码至少8位");if(pw.length()>64)throw error(400,"密码最多64位");if(b.get("id")==null){db.update("INSERT INTO food_account(username,password_hash,name,role,store_id,supplier_id,active) VALUES(?,?,?,?,?,?,?)",username,passwords.encode(pw),name,r,store,supplier,active);}else{long aid=id(b.get("id"));if(aid==id(u.get("id"))&&(!r.equals("admin")||active==0))throw error(409,"不能停用或降级当前管理员账号");one("SELECT id FROM food_account WHERE id=?",aid);db.update("UPDATE food_account SET username=?,name=?,role=?,store_id=?,supplier_id=?,active=? WHERE id=?",username,name,r,store,supplier,active,aid);if(!pw.isEmpty())db.update("UPDATE food_account SET password_hash=? WHERE id=?",passwords.encode(pw),aid);db.update("DELETE FROM food_session WHERE account_id=?",aid);}}
    @Transactional public void profile(Map<String,Object> u,Map<String,Object> b){if(b.containsKey("newPassword")){String old=required(b,"oldPassword",100),pw=required(b,"newPassword",64);if(!passwords.matches(old,str(u,"password_hash")))throw error(400,"原密码不正确");if(pw.length()<8)throw error(400,"新密码至少8位");db.update("UPDATE food_account SET password_hash=? WHERE id=?",passwords.encode(pw),u.get("id"));db.update("DELETE FROM food_session WHERE account_id=?",u.get("id"));return;}role(u,"store");String contact=required(b,"contact",50),phone=required(b,"phone",30),address=required(b,"address",300);if(!phone.matches("[0-9+() -]{6,30}"))throw error(400,"联系电话格式不正确");db.update("UPDATE food_store SET contact=?,phone=?,address=? WHERE id=?",contact,phone,address,u.get("store_id"));}
    public void feedback(Map<String,Object> u,Map<String,Object> b){db.update("INSERT INTO food_feedback(account_id,content,created_at) VALUES(?,?,NOW())",u.get("id"),required(b,"content",1000));}
}
