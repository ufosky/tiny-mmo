package org.example

import akka.actor._
import akka.zeromq._
import models.{Id, World}
import org.apache.thrift.transport.TIOStreamTransport
import java.io.ByteArrayInputStream
import org.apache.thrift.protocol.TBinaryProtocol
import com.typesafe.config.ConfigFactory

// 'brew install zeromq'
object Service {

  // org.example.Service.deserialize(akka.zeromq.ZMQMessage(Seq(akka.zeromq.Frame(Seq(1:Byte)), akka.zeromq.Frame(Seq.empty[Byte]), akka.zeromq.Frame(Seq(0:Byte)), akka.zeromq.Frame(Seq(11,0,1,0,0,0,4,104,111,103,101,0).map(_.toByte)))))
  def deserialize(m: ZMQMessage): AnyRef = {
    m match {
      case ZMQMessage(Seq(identity, empty, header, body@_*)) =>
        header.payload match {
          case Seq(hint) =>
              val bytes = body.map(_.payload.toArray).reduce(_++_)
              val bais = new java.io.ByteArrayInputStream(bytes)
              val transport = new TIOStreamTransport(bais)
              val protocol = new TBinaryProtocol(transport)

              hint match {
              case 0 =>
                val d = new serializers.thrift.Join()
                d.read(protocol)
                d
              case 1 =>
                val d = new serializers.thrift.Forward()
                d.read(protocol)
                d
              case 2 =>
                val d = new serializers.thrift.Leave()
                d.read(protocol)
                d
              case unexpected =>
                throw new RuntimeException("Unexpected hint: " + hint)
            }
          case unexpected =>
            throw new RuntimeException("Unexpected format header format: " + header.payload)
        }
    }

  }

  type Recipient = Frame

  case class Message[A](recipient: Recipient, body: A)

  // org.example.Service.serialize(org.example.Service.Message(akka.zeromq.Frame(Seq(1:Byte)), new serializers.thrift.Join("hoge")))
  def serialize(m: Message[AnyRef]): ZMQMessage = {
    val baos = new java.io.ByteArrayOutputStream()
    val transport = new TIOStreamTransport(baos)
    val protocol = new TBinaryProtocol(transport)
    def compose(hint: Byte) = {
      ZMQMessage(Seq(m.recipient, Frame(Seq.empty), Frame(Seq(hint)), Frame(baos.toByteArray.toSeq)))
    }
    m.body match {
      case b: serializers.thrift.Join =>
        b.write(protocol)
        compose(0)
      case b: serializers.thrift.Forward =>
        b.write(protocol)
        compose(1)
      case b: serializers.thrift.Leave =>
        b.write(protocol)
        compose(2)
      case unexpected =>
        throw new RuntimeException("Couldn't serialize an unexpected body: " + m.body)
    }
  }

  val config = ConfigFactory.load()
  val system = ActorSystem.create("zmqsystem", config)
  // You must share this extension as 'context' to enable inproc:// transport
  // as inproc:// transports messages across threads sharing the same context.
  val extension = ZeroMQExtension(system)

  val authRouter = system.actorOf(Props[WorldRouter], name = "authRouter")
  val authDealer = system.actorOf(Props[WorldDealer], name = "authDealer")
  val authWorker = system.actorOf(Props[WorldWorker], name = "authWorker")
  val authReq = system.actorOf(Props[WorldReq], name = "authReq")

  val router = extension.newRouterSocket(Array(Bind("tcp://*:5560"), Listener(authRouter)))
  val dealer = extension.newDealerSocket(Array(Bind("inproc://workers"), Listener(authDealer)))
  val worker = extension.newDealerSocket(Array(Connect("inproc://workers"), Listener(authWorker)))
  val client = extension.newReqSocket(Array(Connect("tcp://127.0.0.1:5560"), Listener(authReq)))

  class WorldReq extends Actor with ActorLogging {
    override def preStart() {
      println("Req starting up on thread: " + Thread.currentThread().getName)
    }

    def receive: Receive = {
      case Connecting =>
        log.info("Connecting")
      case m: String =>
        log.info("message: " + m)
        client ! ZMQMessage(Seq(Frame(m)))
      case unexpected =>
        log.warning("Unexpected " + unexpected)
    }

    override def postStop() {
      println("postStop")
    }
  }

  /**
   * The router accepts client connections.
   */
  class WorldRouter extends Actor with ActorLogging {

    override def preStart() {
      println("Router starting up on thread: " + Thread.currentThread().getName)
    }

    def receive: Receive = {
      case m @ ZMQMessage(frames @ Seq(identity, _, body @_*)) =>
        log.info("message: " + m)
        dealer ! m
      case unexpected =>
        log.warning("Unexpected " + unexpected)
    }

    override def postStop() {
      println("postStop")
    }
  }

  /**
   * The dealer connects to router
   */
  class WorldDealer extends Actor with ActorLogging {

    override def preStart() {
      println("Dealer starting up on thread: " + Thread.currentThread().getName)
    }

    def receive: Receive = {
      case m @ ZMQMessage(frames @ Seq(identity, _, body @_*)) =>
        log.info("message: " + m)
        router ! m
      case unexpected =>
        log.warning("Unexpected " + unexpected)
    }

    override def postStop() {
      println("postStop")
    }
  }

  /**
   * The worker connects to the dealer
   */
  class WorldWorker extends Actor with ActorLogging {
    override def preStart() {
      println("Worker starting up on thread: " + Thread.currentThread().getName)
    }

    def receive: Receive = {
      case m @ ZMQMessage(Seq(identity, _, frames @_*)) =>
        log.info("message: " + m)
        val idFrame = identity
        val emptyFrame = m.frames(1)
        val id = Id.fromByteArray(identity.payload.toArray)
        deserialize(m) match {
          case m: serializers.thrift.Join =>
            val p = World.join(
              nickname = m.name,
              id = id.toString
            )
            // Notify the client that the client has successfully joined
            worker ! ZMQMessage(Seq(idFrame, emptyFrame, Frame("authenticated")))
            // Notify all currently connected clients that the new client has joined
            World.findExcept(id.toString).foreach { p =>
              val recipient = Frame(Id.fromString(p.id).toByteArray)
              val mm = serialize(Message(recipient, new serializers.thrift.Join(p.nickname)))
              worker ! mm
            }
          case m: serializers.thrift.Forward =>
            World.move(id = id.toString, x = m.dx)
          case m: serializers.thrift.Leave =>
            World.leave(id = id.toString)
        }
      case unexpected =>
        log.warning("Unexpected " + unexpected)
    }

    override def postStop() {
      println("postStop")
    }
  }

}
