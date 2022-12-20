package com.almond.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.almond.config.RedissonConfig;
import com.almond.dto.Result;
import com.almond.dto.UserDTO;
import com.almond.entity.SeckillVoucher;
import com.almond.entity.Voucher;
import com.almond.entity.VoucherOrder;
import com.almond.mapper.VoucherOrderMapper;
import com.almond.service.ISeckillVoucherService;
import com.almond.service.IVoucherOrderService;
import com.almond.service.IVoucherService;
import com.almond.utils.RedisIdWorker;
import com.almond.utils.SimpleRedisLock;
import com.almond.utils.UserHolder;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService iSeckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;




    //任务线程
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    //代理对象
    private IVoucherOrderService proxy;

    @PostConstruct //在spring创建了类之后就开始执行任务(因为请求随时都可能来)
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{
        String queueName = "stream.orders"; //redis中消息队列key
        @Override
        public void run() {
            while (true){
                try {
                    //1.获取队列中的订单信息 消息队列创建命令xgroup create stream.orders g1 0 MKSTREAM
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //2.判断消息是否获取成功
                    if(list==null || list.isEmpty()){
                        //2.1 失败,则继续循环
                        continue;
                    }
                    //转换为bean
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> map = record.getValue();
                    VoucherOrder order = BeanUtil.fillBeanWithMap(map, new VoucherOrder(), true);
                    //3.成功,完成下单
                    handleVoucherOrder(order);
                    //4.ack确认消息 record.getId()是消息id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1",record.getId());
                } catch (Exception e) {
                    log.info("处理订单时出现异常",e);
                    handlePendingList();
                }
            }
        }
        //处理异常消息
        private void handlePendingList() {
            while (true){
                try {
                    //1.获取pendingList异常链表中的信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //2.判断异常消息是否获取成功
                    if(list==null || list.isEmpty()){
                        //2.1 没有异常消息,跳出循环
                        break;
                    }
                    //转换为bean
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> map = record.getValue();
                    VoucherOrder order = BeanUtil.fillBeanWithMap(map, new VoucherOrder(), true);
                    //3.成功,完成下单
                    handleVoucherOrder(order);
                    //4.ack确认消息 record.getId()是消息id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1",record.getId());
                } catch (Exception e) {
                    log.info("处理异常列表时出现异常",e);
                    //休眠后循环处理异常消息
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException interruptedException) {
                        interruptedException.printStackTrace();
                    }
                }
            }
        }
    }
//    //将下单更新到数据库的任务
//    //装有订单信息的阻塞队列
//    private ArrayBlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue(1024*1024);
//    private class VoucherOrderHandler implements Runnable{
//
//        @Override
//        public void run() {
//            while (true){
//                try {
//                //1.获取队列中的订单信息
//                VoucherOrder order = orderTasks.take();
//                //2.创建订单
//                handleVoucherOrder(order);
//                } catch (Exception e) {
//                    log.info("创建订单时出现异常",e);
//                }
//            }
//        }
//    }

    private void handleVoucherOrder(VoucherOrder order) {
        //使用redisson获取分布式锁
        //不能再从UserHolder获取用户id,因为线程不一样,threadLocal不可用
        Long userId = order.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean hasLock = lock.tryLock();
        if(!hasLock){
            log.error("不能重复下单");
            return;
        }
        try {
            //无法获取代理对象,因为这里已经是另一个线程,因此将其放在成员变量中
            proxy.createVoucherOrder(order);
        } finally {
            lock.unlock();
        }
    }


    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static{
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //生成订单id
        long orderId = redisIdWorker.nextId("order");
        //执行lua脚本,lua负责判断用户是否有购买资格,库存是否足够,并将购买消息放入消息队列
        Long res = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );
        int result = res.intValue();
        if( result != 0){
            return Result.fail(res == 1 ? "库存不足":"不能重复购买");
        }
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回订单信息
        return Result.ok(orderId);
    }

//    public Result seckillVoucher(Long voucherId) {
//        Long userId = UserHolder.getUser().getId();
//        Long res = stringRedisTemplate.execute(SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(),
//                userId.toString()
//        );
//        if(res !=0){
//            return Result.fail(res == 1 ? "库存不足":"不能重复购买");
//        }
//        //有购买资格,将对数据库的更新操作放入队列中
//        // 1.新建订单id
//        VoucherOrder voucherOrder = new VoucherOrder();
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setVoucherId(orderId);
//        // 2.当前用户的id
//        voucherOrder.setUserId(userId);
//        // 3.当前代金券id
//        voucherOrder.setVoucherId(voucherId);
//        // 4.阻塞队列
//        orderTasks.add(voucherOrder);
//        //获取代理对象
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        //返回订单信息
//        return Result.ok(orderId);
//    }



//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //1.查询优惠券
//        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);
//        //2.查询秒杀是否开始
//        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("秒杀尚未开始");
//        }
//        //3.查询秒杀是否结束
//        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("秒杀已经结束");
//        }
//        //4.查询优惠券库存是否充足
//        if(voucher.getStock() < 1){
//            return Result.fail("库存不足");
//        }
//        UserDTO user = UserHolder.getUser();
//        //实现一人一单
//        //以当前用户的id字符串在常量池的引用(intern方法是在常量池中找对应字符串)为锁,这样同一个用户就不会并发,不同用户不会串行
//        //实现一人一单,集群情况下使用分布式锁
//        //获取分布式锁
//        //使用redisson获取分布式锁
//        RLock lock = redissonClient.getLock("lock:order:" + user.getId());
//        boolean hasLock = lock.tryLock();
//        if(!hasLock){
//            return Result.fail("不能重复下单");
//        }
//        try {
//            IVoucherOrderService iVoucherOrderService = (IVoucherOrderService) AopContext.currentProxy();
//            return iVoucherOrderService.createVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }
//    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //查询当前用户订单
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if(count>0){
            log.error("用户已经购买过了,不能重复购买");
            return;
        }
        //5.扣减库存
        boolean success = iSeckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                //针对本业务对cas方法优化,不再要求库存与之前查出的一致,而是库存大于0,就进行扣减
                .update();
        if(!success){
            log.error("库存不足");
            return;
        }
        // 6.4存入当前订单
        save(voucherOrder);
    }


}
