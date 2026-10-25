package com.ruoyi.web.controller.food;

import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.config.RuoYiConfig;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.dao.DuplicateKeyException;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.file.*;
import java.util.*;

@Anonymous
@RestController
@RequestMapping("/food")
public class FoodController {
    private final FoodService service;
    public FoodController(FoodService service){this.service=service;}
    private Map<String,Object> user(String token){return service.user(token);}
    private Map<String,Object> ok(Object data){return FoodService.map("code",200,"data",data);}
    @GetMapping("/health") public Object health(){return ok(FoodService.map("status","UP","service","食材配送"));}
    @GetMapping(value="/assets/food-atlas.png", produces=MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<org.springframework.core.io.Resource> atlas(){return ResponseEntity.ok().cacheControl(CacheControl.maxAge(1,java.util.concurrent.TimeUnit.DAYS)).body(new org.springframework.core.io.ClassPathResource("food-assets/food-atlas.png"));}
    @PostMapping("/auth/login") public Object login(@RequestBody Map<String,Object> b){return ok(service.login(b));}
    @PostMapping("/auth/logout") public Object logout(@RequestHeader("X-Food-Token") String t){user(t);service.logout(t);return ok(true);}
    @GetMapping("/bootstrap") public Object bootstrap(@RequestHeader("X-Food-Token") String t){return ok(service.bootstrap(user(t)));}
    @PostMapping("/cart") public Object cart(@RequestHeader("X-Food-Token") String t,@RequestBody Map<String,Object> b){service.cart(user(t),b);return ok(true);}
    @PostMapping("/orders") public Object order(@RequestHeader("X-Food-Token") String t,@RequestBody Map<String,Object> b){return ok(service.createOrder(user(t),b));}
    @PostMapping("/orders/{id}/action") public Object action(@RequestHeader("X-Food-Token") String t,@PathVariable long id,@RequestBody Map<String,Object> b){service.action(user(t),id,b);return ok(true);}
    @PostMapping("/orders/{id}/picking") public Object picking(@RequestHeader("X-Food-Token") String t,@PathVariable long id,@RequestBody Map<String,Object> b){service.picking(user(t),id,b);return ok(true);}
    @PostMapping("/products") public Object product(@RequestHeader("X-Food-Token") String t,@RequestBody Map<String,Object> b){return ok(service.saveProduct(user(t),b));}
    @PostMapping("/stores") public Object store(@RequestHeader("X-Food-Token") String t,@RequestBody Map<String,Object> b){service.saveEntity(user(t),"stores",b);return ok(true);}
    @PostMapping("/suppliers") public Object supplier(@RequestHeader("X-Food-Token") String t,@RequestBody Map<String,Object> b){service.saveEntity(user(t),"suppliers",b);return ok(true);}
    @PostMapping("/bindings") public Object binding(@RequestHeader("X-Food-Token") String t,@RequestBody Map<String,Object> b){service.binding(user(t),b);return ok(true);}
    @PostMapping("/settlements") public Object settlement(@RequestHeader("X-Food-Token") String t,@RequestBody Map<String,Object> b){service.settlement(user(t),b);return ok(true);}
    @PostMapping("/accounts") public Object account(@RequestHeader("X-Food-Token") String t,@RequestBody Map<String,Object> b){service.account(user(t),b);return ok(true);}
    @PostMapping("/profile") public Object profile(@RequestHeader("X-Food-Token") String t,@RequestBody Map<String,Object> b){service.profile(user(t),b);return ok(true);}
    @PostMapping("/feedback") public Object feedback(@RequestHeader("X-Food-Token") String t,@RequestBody Map<String,Object> b){service.feedback(user(t),b);return ok(true);}
    @PostMapping("/upload") public Object upload(@RequestHeader("X-Food-Token") String t,@RequestParam("file") MultipartFile file) throws IOException {
        if(!"admin".equals(FoodService.str(user(t),"role")))throw FoodService.error(403,"只有平台可上传食材图片");
        if(file.isEmpty()||file.getSize()>5*1024*1024)throw FoodService.error(400,"请上传5MB以内的图片");
        byte[] data=file.getBytes();java.awt.image.BufferedImage decoded=ImageIO.read(new ByteArrayInputStream(data));if(decoded==null||decoded.getWidth()>6000||decoded.getHeight()>6000)throw FoodService.error(400,"请选择有效的JPG或PNG图片");
        Path folder=Paths.get(RuoYiConfig.getProfile(),"food");Files.createDirectories(folder);String name=UUID.randomUUID()+".png";ImageIO.write(decoded,"png",folder.resolve(name).toFile());return ok(FoodService.map("image","/profile/food/"+name));
    }
    @GetMapping("/export") public ResponseEntity<byte[]> export(@RequestHeader("X-Food-Token") String t,@RequestParam(defaultValue="orders") String type,@RequestParam(defaultValue="") String from,@RequestParam(defaultValue="") String to,@RequestParam(defaultValue="") String storeId,@RequestParam(defaultValue="") String supplierId,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String search,@RequestParam(defaultValue="false") boolean excludeCancelled) throws IOException {
        Map<String,Object> u=user(t);List<Map<String,Object>> orders=service.orders(u);boolean supplier="supplier".equals(u.get("role"));List<List<String>> rows=new ArrayList<>();
        // Apply the same filters to every export after server-side account scoping.
        orders.removeIf(o -> {
            String date=String.valueOf(o.get("created_at")).substring(0,10);
            if((!from.isEmpty()&&date.compareTo(from)<0)||(!to.isEmpty()&&date.compareTo(to)>0)||(!storeId.isEmpty()&&!storeId.equals(String.valueOf(o.get("store_id"))))||(!supplierId.isEmpty()&&!supplierId.equals(String.valueOf(o.get("supplier_id")))))return true;
            if((!status.isEmpty()&&!status.equals(o.get("status")))||(excludeCancelled&&"cancelled".equals(o.get("status"))))return true;
            if(search.isEmpty()||String.valueOf(o.get("order_no")).contains(search)||String.valueOf(o.get("storeName")).contains(search))return false;
            return ((List<Map<String,Object>>)o.get("items")).stream().noneMatch(i->String.valueOf(i.get("name")).contains(search));
        });
        rows.add(Arrays.asList("订单编号","门店","供应商","状态","食材","数量","单位",supplier?"":"单价",supplier?"":"金额","配送日期","收货地址"));
        for(Map<String,Object> o:orders){String date=String.valueOf(o.get("created_at")).substring(0,10);if((!from.isEmpty()&&date.compareTo(from)<0)||(!to.isEmpty()&&date.compareTo(to)>0)||(!storeId.isEmpty()&&!storeId.equals(String.valueOf(o.get("store_id"))))||(!supplierId.isEmpty()&&!supplierId.equals(String.valueOf(o.get("supplier_id")))))continue;for(Map<String,Object> i:(List<Map<String,Object>>)o.get("items"))rows.add(Arrays.asList(String.valueOf(o.get("order_no")),String.valueOf(o.get("storeName")),String.valueOf(o.get("supplierName")),String.valueOf(o.get("status")),String.valueOf(i.get("name")),String.valueOf(i.get("quantity")),String.valueOf(i.get("unit")),supplier?"":String.valueOf(i.get("price")),supplier?"":String.valueOf(i.get("amount")),String.valueOf(o.get("delivery_date")),String.valueOf(o.get("address"))));}
        if("stock".equals(type)){rows.clear();rows.add(Arrays.asList("食材","累计配送数量","单位"));Map<String,Map<String,Object>> stock=new LinkedHashMap<>();for(Map<String,Object> o:orders){String date=String.valueOf(o.get("created_at")).substring(0,10);if(!"completed".equals(o.get("status"))||(!from.isEmpty()&&date.compareTo(from)<0)||(!to.isEmpty()&&date.compareTo(to)>0)||(!storeId.isEmpty()&&!storeId.equals(String.valueOf(o.get("store_id")))))continue;for(Map<String,Object> i:(List<Map<String,Object>>)o.get("items")){String k=String.valueOf(i.get("productId"))+":"+String.valueOf(i.get("unit"));if(!stock.containsKey(k))stock.put(k,FoodService.map("name",i.get("name"),"unit",i.get("unit"),"quantity",java.math.BigDecimal.ZERO));Map<String,Object> s=stock.get(k);s.put("quantity",((java.math.BigDecimal)s.get("quantity")).add(FoodService.money(i.get("quantity"))));}}for(Map<String,Object> s:stock.values())rows.add(Arrays.asList(String.valueOf(s.get("name")),String.valueOf(s.get("quantity")),String.valueOf(s.get("unit"))));}
        if("settlements".equals(type)){if(supplier)throw FoodService.error(403,"无权查看结算记录");rows.clear();rows.add(Arrays.asList("门店","结算金额","结算日期","备注"));Map<String,Object> data=service.bootstrap(u);for(Map<String,Object> s:(List<Map<String,Object>>)data.get("settlements")){String date=String.valueOf(s.get("settlement_date"));if((!from.isEmpty()&&date.compareTo(from)<0)||(!to.isEmpty()&&date.compareTo(to)>0)||(!storeId.isEmpty()&&!storeId.equals(String.valueOf(s.get("store_id")))))continue;rows.add(Arrays.asList(String.valueOf(s.get("storeName")),String.valueOf(s.get("amount")),date,String.valueOf(s.get("note"))));}}
        try(XSSFWorkbook book=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()){Sheet sheet=book.createSheet("食材配送明细");for(int j=0;j<rows.size();j++){Row row=sheet.createRow(j);for(int k=0;k<rows.get(j).size();k++){row.createCell(k).setCellValue(rows.get(j).get(k));sheet.setColumnWidth(k,5600);}}sheet.createFreezePane(0,1);book.write(out);return ResponseEntity.ok().header("Content-Disposition","attachment; filename=food-report.xlsx").contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(out.toByteArray());}
    }
    @ExceptionHandler(FoodService.Failure.class) public ResponseEntity<?> failure(FoodService.Failure e){return ResponseEntity.status(e.status).body(FoodService.map("code",e.status,"message",e.getMessage()));}
    @ExceptionHandler(DuplicateKeyException.class) public ResponseEntity<?> duplicate(){return ResponseEntity.status(409).body(FoodService.map("code",409,"message","账号或记录已存在，请勿重复提交"));}
}
