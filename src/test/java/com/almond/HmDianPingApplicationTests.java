package com.almond;

import com.almond.entity.Shop;
import com.almond.service.impl.ShopServiceImpl;
import com.almond.utils.RedisIdWorker;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static com.almond.utils.RedisConstants.SHOP_GEO_KEY;

@RunWith(SpringRunner.class)
@SpringBootTest
public class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    public void testShopCacheRebuild(){
        //预处理商店数据,全部放入redis
        for (Long i = 1L; i <= 14L; i++) {
            shopService.saveShop2Redis(i,10L); //逻辑过期时间10秒
        }
    }

    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    public void testId() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(300);
        Runnable task = () ->{
            for (int i = 0; i < 100; i++) {
                long order = redisIdWorker.nextId("order");
                System.out.println(order);
            }
            countDownLatch.countDown();
        };
        long start = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        countDownLatch.await();
        long end = System.currentTimeMillis();
        System.out.println("time="+(end - start));
    }

    //导入商铺地理坐标
    @Test
    public void loadGEOLocations(){
        //1.查询店铺信息
        List<Shop> shops = shopService.list();
        //2.对店铺按照店铺类型进行分类
        Map<Long, List<Shop>> map = shops.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //3.分批次将不同类型的店铺集合写入到redis中
        for (Map.Entry<Long,List<Shop>> entry : map.entrySet()){
            Long shopType = entry.getKey();
            String key = SHOP_GEO_KEY + shopType;
            List<Shop> value = entry.getValue();
            ArrayList<RedisGeoCommands.GeoLocation<String>> geoLocations =
                    new ArrayList<>(value.size());
            for (Shop shop:value) {
                geoLocations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY()))
                );
            }
            stringRedisTemplate.opsForGeo().add(key, geoLocations);
        }
    }
}
