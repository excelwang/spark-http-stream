import org.apache.spark.SparkConf
import org.apache.spark.serializer.KryoSerializer
import org.apache.spark.sql.Row
import org.apache.spark.sql.execution.streaming.http.HttpStreamClient
import org.junit.Assert
import org.junit.Test
import org.apache.spark.sql.types.LongType
import org.apache.spark.sql.types.IntegerType
import org.apache.spark.sql.types.DoubleType
import org.apache.spark.sql.types.BooleanType
import org.apache.spark.sql.types.FloatType
import org.apache.spark.sql.types.StringType
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.types.StructField
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types.ByteType
import org.apache.spark.sql.execution.streaming.http.HttpStreamServer
import org.apache.spark.sql.execution.streaming.http.StreamPrinter
import org.apache.spark.sql.execution.streaming.http.HttpStreamServerSideException

/**
 * tests HttpStreamServer/Client
 */
class HttpStreamServerClientTest {
	val ROWS1 = Array(Row("hello1", 1, true, 0.1f, 0.1d, 1L, '1'.toByte),
		Row("hello2", 2, false, 0.2f, 0.2d, 2L, '2'.toByte),
		Row("hello3", 3, true, 0.3f, 0.3d, 3L, '3'.toByte));

	val ROWS2 = Array(Row("hello"),
		Row("world"),
		Row("bye"),
		Row("world"));

	@Test
	def testHttpStreamIO() {
		//starts a http server
		val kryoSerializer = new KryoSerializer(new SparkConf());
		val server = HttpStreamServer.start("/xxxx", 8080);

		val spark = SparkSession.builder.appName("testHttpTextSink").master("local[4]")
			.getOrCreate();
		spark.conf.set("spark.sql.streaming.checkpointLocation", "/tmp/");

		val sqlContext = spark.sqlContext;
		import spark.implicits._
		//add a local message buffer to server, with 2 topics registered
		server.withBuffer()
			.addListener(new StreamPrinter())
			.createTopic[(String, Int, Boolean, Float, Double, Long, Byte)]("topic-1")
			.createTopic[String]("topic-2");

		val client = HttpStreamClient.connect("http://localhost:8080/xxxx");
		//tests schema of topics
		val schema1 = client.fetchSchema("topic-1");
		Assert.assertArrayEquals(Array[Object](StringType, IntegerType, BooleanType, FloatType, DoubleType, LongType, ByteType),
			schema1.fields.map(_.dataType).asInstanceOf[Array[Object]]);

		val schema2 = client.fetchSchema("topic-2");
		Assert.assertArrayEquals(Array[Object](StringType),
			schema2.fields.map(_.dataType).asInstanceOf[Array[Object]]);

		//prepare to consume messages
		val sid1 = client.subscribe("topic-1")._1;
		val sid2 = client.subscribe("topic-2")._1;

		//produces some data
		client.sendRows("topic-1", 1, ROWS1);

		val sid4 = client.subscribe("topic-1")._1;
		val sid5 = client.subscribe("topic-2")._1;

		client.sendRows("topic-2", 1, ROWS2);

		//consumes data
		val fetched = client.fetchStream(sid1).map(_.originalRow);
		Assert.assertArrayEquals(ROWS1.asInstanceOf[Array[Object]], fetched.asInstanceOf[Array[Object]]);
		//it is empty now
		Assert.assertArrayEquals(Array[Object](), client.fetchStream(sid1).map(_.originalRow).asInstanceOf[Array[Object]]);
		Assert.assertArrayEquals(ROWS2.asInstanceOf[Array[Object]], client.fetchStream(sid2).map(_.originalRow).asInstanceOf[Array[Object]]);
		Assert.assertArrayEquals(Array[Object](), client.fetchStream(sid4).map(_.originalRow).asInstanceOf[Array[Object]]);
		Assert.assertArrayEquals(ROWS2.asInstanceOf[Array[Object]], client.fetchStream(sid5).map(_.originalRow).asInstanceOf[Array[Object]]);
		Assert.assertArrayEquals(Array[Object](), client.fetchStream(sid5).map(_.originalRow).asInstanceOf[Array[Object]]);

		client.unsubscribe(sid4);
		try {
			client.fetchStream(sid4);
			//exception should be thrown, because subscriber id is invalidated
			Assert.assertTrue(false);
		}
		catch {
			case e: Throwable ⇒
				e.printStackTrace();
				Assert.assertEquals(classOf[HttpStreamServerSideException], e.getClass);
		}

		server.stop();
	}
}
