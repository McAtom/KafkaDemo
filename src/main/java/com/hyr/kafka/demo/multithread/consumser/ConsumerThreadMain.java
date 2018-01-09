package com.hyr.kafka.demo.multithread.consumser;

import com.hyr.kafka.demo.utils.RedisUtil;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import redis.clients.jedis.Jedis;

import java.io.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/*******************************************************************************
 * @date 2018-01-08 上午 15:15
 * @author: <a href=mailto:huangyr@bonree.com>黄跃然</a>
 * @Description: 多线程消费DEMO
 ******************************************************************************/
public class ConsumerThreadMain {

    public static LinkedBlockingQueue<MultiThreadConsumer.CustomMessage> jobQueue;

    public static LinkedBlockingQueue<MultiThreadConsumer.CustomMessage> jobQueueRedis;

    public static ExecutorService pool = null;

    private static AtomicBoolean isInsertStop;

    private static Jedis redis;

    private static String INSERT_QUEUE_CATCH_KEY = "INSERT_QUEUE_CATCH_KEY";

    private static CountDownLatch countDownLatch; // 等待消费redis的线程

    private static Long redisQueueLen;

    public static void main(String[] args) throws Exception {
        String brokers = "localhost:9092";
        String groupId = "0";
        final String topic = "testoffsetp5";
        int consumerNumber = 5;

        jobQueue = new LinkedBlockingQueue<MultiThreadConsumer.CustomMessage>(30);

        isInsertStop = new AtomicBoolean(false);

        countDownLatch = new CountDownLatch(consumerNumber + 1);

        // 安全关闭Consumer
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    isInsertStop.set(true);

                    System.out.println("Shut Down Hook Runing......");
                    MultiThreadConsumer.instance.stop();

                    if (pool != null) {
                        pool.shutdown();
                    }

                    try {
                        redis = RedisUtil.getJedis();
                        // 将队列中未处理完毕的消息进行保存到redis
                        for (int i = 0; i < jobQueue.size(); i++) {
                            System.out.println("start write to redis! left:" + jobQueue.size());
                            MultiThreadConsumer.CustomMessage message = jobQueue.take();
                            // object to bytearray
                            ByteArrayOutputStream bo = new ByteArrayOutputStream();
                            ObjectOutputStream oo = new ObjectOutputStream(bo);
                            oo.writeObject(message);
                            byte[] bytes = bo.toByteArray();
                            redis.lpush(INSERT_QUEUE_CATCH_KEY.getBytes("utf-8"), bytes);
                        }
                        System.out.println("write to redis complete!");
                    } catch (UnsupportedEncodingException e) {
                        e.printStackTrace();
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        if (redis != null) {
                            redis.close();
                        }
                    }

                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }));

        MultiThreadConsumer.instance.init(brokers, groupId, topic);

        runInsertJobOfRedis(consumerNumber);

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                // 消费redis数据
                try {
                    redis = RedisUtil.getJedis();
                    redisQueueLen = redis.llen(INSERT_QUEUE_CATCH_KEY.getBytes("utf-8"));
                    jobQueueRedis = new LinkedBlockingQueue<MultiThreadConsumer.CustomMessage>(30);
                    while (redis.llen(INSERT_QUEUE_CATCH_KEY.getBytes("utf-8")) > 0) {
                        System.out.println("redis size:" + redis.llen(INSERT_QUEUE_CATCH_KEY.getBytes("utf-8")));
                        byte[] bytes = redis.lpop(INSERT_QUEUE_CATCH_KEY.getBytes("utf-8"));
                        ByteArrayInputStream bi = new ByteArrayInputStream(bytes);
                        ObjectInputStream oi = new ObjectInputStream(bi);
                        MultiThreadConsumer.CustomMessage message = (MultiThreadConsumer.CustomMessage) oi.readObject();
                        System.out.println("consumer redis !" + message.offsetAndMetadataMap.get(message.partition).offset());
                        jobQueueRedis.put(message);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                } finally {
                    if (redis != null) {
                        redis.close();
                        System.out.println("redis queue is clear!");
                    }
                    countDownLatch.countDown();
                }
            }
        });
        thread.setDaemon(true);
        thread.start();

        System.out.println("wait ......");
        countDownLatch.await(); // 等待redis消费完毕
        System.out.println("wait ok !");

        runInsertJob(consumerNumber); // 多线程入库
        MultiThreadConsumer.instance.start(consumerNumber);


        Thread.currentThread().join();
    }

    // 消费redis取出来的消息数据
    private static void runInsertJobOfRedis(int INSERT_THREAD_NUM) {
        System.out.println("runInsertJobOfRedis start run insert job ! ");
        pool = new ThreadPoolExecutor(INSERT_THREAD_NUM, INSERT_THREAD_NUM, 0, TimeUnit.SECONDS, new LinkedBlockingDeque<Runnable>(INSERT_THREAD_NUM), new RejectedExecutionHandler() {
            @Override
            public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
                try {
                    if (!executor.isShutdown()) {
                        executor.getQueue().put(r);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });

        for (int i = 0; i < INSERT_THREAD_NUM; i++) {
            pool.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        while (redisQueueLen > 0) {
                            System.out.println("Start insert!");
                            System.out.println("jobQueueRedis.size():" + jobQueueRedis.size());
                            MultiThreadConsumer.CustomMessage message = jobQueueRedis.take();
                            Map<TopicPartition, OffsetAndMetadata> offsetAndMetadataMap = message.offsetAndMetadataMap;
                            TopicPartition partition = message.partition;
                            // 消息处理完成,消费成功。系统自身的提交offset。
                            insertAtomicDB(message.v1, message.v2, offsetAndMetadataMap, partition);
                            MultiThreadConsumer.instance.doCommit(offsetAndMetadataMap);
                            redisQueueLen--;
                            System.out.println("Complete consumer ! partition:" + partition + " offser:" + offsetAndMetadataMap.get(partition).offset());
                            System.out.println("Complete insert offset:" + offsetAndMetadataMap.get(partition).offset());
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } finally {
                        countDownLatch.countDown();
                    }
                }
            });
        }
    }

    private static void runInsertJob(int INSERT_THREAD_NUM) {
        System.out.println("runInsertJob start run insert job ! ");

        for (int i = 0; i < INSERT_THREAD_NUM; i++) {
            pool.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        while (!isInsertStop.get()) {
                            System.out.println("Start insert!");
                            System.out.println("jobQueue.size():" + jobQueue.size());
                            MultiThreadConsumer.CustomMessage message = jobQueue.take();
                            Map<TopicPartition, OffsetAndMetadata> offsetAndMetadataMap = message.offsetAndMetadataMap;
                            TopicPartition partition = message.partition;
                            // 消息处理完成,消费成功。系统自身的提交offset。
                            insertAtomicDB(message.v1, message.v2, offsetAndMetadataMap, partition);
                            MultiThreadConsumer.instance.doCommit(offsetAndMetadataMap);
                            System.out.println("Complete consumer ! partition:" + partition + " offser:" + offsetAndMetadataMap.get(partition).offset());
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            });
        }
    }

    /**
     * 入库
     */
    public static void insertAtomicDB(String v1, String v2, Map<TopicPartition, OffsetAndMetadata> offsetAndMetadataMap, TopicPartition partition) {
        Statement statement = null;
        Connection connection = null;
        // 上一次消费的offset 用于回滚RollBack getOffsetFromDB。     The last consumption of offset was used to roll back

        try {
            String URL = "jdbc:mysql://localhost:3306/kafkatest";
            String USER = "root";
            String PASSWORD = "666666";
            //1.加载驱动程序
            Class.forName("com.mysql.jdbc.Driver");
            //2.获得数据库链接
            connection = DriverManager.getConnection(URL, USER, PASSWORD);

            // 关闭自动提交  Close automatic submission
            connection.setAutoCommit(false);

            // 4.执行插入
            // 4.1 获取操作SQL语句的Statement对象：
            // 调用Connection的createStatement()方法来创建Statement的对象

            // 3.准备插入的SQL语句
            String sql = "INSERT INTO ttt(id,text) "
                    + "VALUES (" + v1 + ",'" + v2 + "')";
            String str1 = " currentThread:" + Thread.currentThread() + " partition:" + partition + " start insert!!!:";
            System.out.println(str1 + "\n" + sql);
            statement = connection.createStatement();

            // 4.2 调用Statement对象的executeUpdate(sql) 执行SQL 语句的插入
            statement.executeUpdate(sql);

            // 提交
            connection.commit();
            System.out.println("Complete insert offset:" + offsetAndMetadataMap.get(partition).offset());

        } catch (SQLException e) {
            if (e.getErrorCode() == 1062) {
                System.out.println("主键重复 重复消费:" + offsetAndMetadataMap.get(partition).offset());
                System.exit(-1);
            }
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // 5.关闭Statement对象
            if (statement != null) {
                try {
                    statement.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
            if (connection != null) {
                // 2.关闭连接
                try {
                    connection.setAutoCommit(true);
                    connection.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}