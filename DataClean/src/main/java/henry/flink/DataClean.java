package henry.flink;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import henry.flink.customSource.MyRedisSource;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.contrib.streaming.state.RocksDBStateBackend;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.co.CoFlatMapFunction;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer011;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaProducer011;
import org.apache.flink.streaming.util.serialization.KeyedSerializationSchemaWrapper;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Properties;

/**
 * @Author: Henry
 * @Description: 数据清洗需要
 *          组装代码
 *
 *  创建kafka topic命令：
 *      ./kafka-topics.sh --create --zookeeper localhost:2181 --replication-factor 1 --partitions 5 --topic allData
 *      ./kafka-topics.sh --create --zookeeper localhost:2181 --replication-factor 1 --partitions 5 --topic allDataClean
 *
 * @Date: Create in 2019/5/25 17:47
 **/
public class DataClean {

    private static Logger logger = LoggerFactory.getLogger(DataClean.class); //log.info() 调用

    public static void main(String[] args) throws Exception{

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // 修改并行度
        env.setParallelism(5);

        //checkpoint配置
        env.enableCheckpointing(60000);  // 设置 1分钟=60秒
        env.getCheckpointConfig().setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE);
        env.getCheckpointConfig().setMinPauseBetweenCheckpoints(30000);
        env.getCheckpointConfig().setCheckpointTimeout(10000);
        env.getCheckpointConfig().setMaxConcurrentCheckpoints(1);
        env.getCheckpointConfig().enableExternalizedCheckpoints(
                CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);

        //设置statebackend
        env.setStateBackend(new RocksDBStateBackend("hdfs://master:9000/flink/checkpoints",true));

        //  指定 Kafka Source
        String topic = "allData";
        Properties prop = new Properties();
        prop.setProperty("bootstrap.servers", "master:9092");
        prop.setProperty("group.id", "con1");
        FlinkKafkaConsumer011<String> myConsumer = new FlinkKafkaConsumer011<String>(
                topic, new SimpleStringSchema(),prop);

        //  获取 Kafka 中的数据，Kakfa 数据格式如下：
        //  {"dt":"2019-01-01 11:11:11", "countryCode":"US","data":[{"type":"s1","score":0.3},{"type":"s1","score":0.3}]}
        DataStreamSource<String> data = env.addSource(myConsumer);    // 并行度根据 kafka topic partition数设定

        //  对数据打平需要对 大区和国家之间的关系进行转换，由于存在对应关系变的可能性，所以不能写死
        //  处理方法：再添加一个Source，把国家和大区之间的关系存到redis数据库中
        //  对于 Redis，官方只提供了 Sink 的支持，没有提供 Source 的支持，所以需要自定义 Source
        //  由于对应关系可能会变，所以隔一段时间从 Redis 取一次最新对应关系
        //  mapData 中存储最新的国家码和大区的映射关系
        DataStream<HashMap<String,String>> mapData = env.addSource(new MyRedisSource())
                .broadcast();    //  可以把数据发送到后面算子的所有并行实际例中进行计算，否则处理数据丢失数据

        //  通过 connect 方法将两个数据流连接在一起,然后再flatMap
        DataStream<String> resData = data.connect(mapData).flatMap(
                                    //参数类型代表：  data ,    mapData         ， 返回结果; Json
                 new CoFlatMapFunction<String, HashMap<String, String>, String>() {
                    //  存储国家和大区的映射关系
                    private HashMap<String, String> allMap = new HashMap<String, String>();

                    //  flatMap1 处理 Kafka 中的数据
                    public void flatMap1(String value, Collector<String> out)
                            throws Exception {
                        //  原数据是 Json 格式
                        JSONObject jsonObject = JSONObject.parseObject(value);
                        String dt = jsonObject.getString("dt");
                        String countryCode = jsonObject.getString("countryCode");
                        //  获取大区
                        String area = allMap.get(countryCode);
                        //  迭代取数据，jsonArray每个数据都是一个jsonobject
                        JSONArray jsonArray = jsonObject.getJSONArray("data");
                        for (int i = 0; i < jsonArray.size(); i++) {
                            JSONObject jsonObject1 = jsonArray.getJSONObject(i);
                            System.out.println("areas : -  " + area);
                            jsonObject1.put("area", area);
                            jsonObject1.put("dt", dt);
                            out.collect(jsonObject1.toJSONString());
                        }
                    }

                    //  flatMap2 处理 Redis 返回的 map 类型的数据
                    public void flatMap2(HashMap<String, String> value, Collector<String> out)
                            throws Exception {
                        this.allMap = value;
                    }
                });

        String outTopic = "allDataClean";
        Properties outprop= new Properties();
        outprop.setProperty("bootstrap.servers", "master:9092");
        //第一种解决方案，设置FlinkKafkaProducer011里面的事务超时时间
        //设置事务超时时间
        outprop.setProperty("transaction.timeout.ms",60000*15+"");
        //第二种解决方案，设置kafka的最大事务超时时间

        FlinkKafkaProducer011<String> myproducer = new FlinkKafkaProducer011<>(outTopic,
                new KeyedSerializationSchemaWrapper<String>(
                        new SimpleStringSchema()), outprop,
                FlinkKafkaProducer011.Semantic.EXACTLY_ONCE);
        resData.addSink(myproducer);

        env.execute("Data Clean");

    }
}
