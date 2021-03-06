package com.github.mumoshu.mmo.server

import org.specs2.mutable._
import java.net.InetSocketAddress
import akka.actor._
import akka.pattern._
import scala.concurrent.{ExecutionContext, Await}
import scala.concurrent.duration._
import akka.util.{ByteString, Timeout}
import com.github.mumoshu.mmo.models.world.world._
import akka.util
import com.github.mumoshu.mmo.testing._
import bot.{GameBotImpl, GameBot}
import com.github.mumoshu.mmo.models.world.world.Position
import com.github.mumoshu.mmo.testing.RecordingGameClientObserver
import com.github.mumoshu.mmo.thrift
import scala.concurrent.ExecutionContext.Implicits.global

object Tester {

  def test = {
    val system = ActorSystem("client")
    val server = TCPIPServer.server
    Thread.sleep(1100)
    val client = system.actorOf(Props[GameClient])
    Thread.sleep(1100)
    implicit val timeout = Timeout(5 seconds)
    val bytes = TCPIPServer.protocol.serialize(new thrift.message.Join("mumoshu"))
    var r: Option[Boolean] = None
    try {
      val result = Await.result((client ? bytes.asInstanceOf[ByteString]).mapTo[Boolean], timeout.duration)
      r = Some(result)
    } catch {
      case e =>
      r = None
    } finally {
      server ! "STOP"
    }
    r
  }
}

object TCPIPServerSpec extends Specification {

  "TCPIPServer" should {

    "accept joining user" in {

//      val result = Tester.test
//      result must be some
//
      Thread.sleep(1100)

//      TCPIPServer.world.things must be size(1)
    }

    "integration" in new After {

      val system = ActorSystem("integration")
      val port = 1235
      val server = TCPIPServer.createServer(port)
      val serverAddress = new InetSocketAddress("localhost", port)

      implicit val timeout = util.Timeout(FiniteDuration(5, concurrent.duration.SECONDS))

      def makeTcpIpBot(name: String): (GameBot, GameClientObserver) = {
        val observer = RecordingGameClientObserver()
        val client = system.actorOf(Props(new GameClient(serverAddress, observer)))
        (
          TypedActor(system).typedActorOf(
            props = TypedProps(classOf[GameBot], new GameBotImpl(client, name)),
            name = "gameBot-" + name
          ),
          observer
        )
      }

      def makeAkkaBot(name: String): (GameBot, GameClientObserver) = {
        val observer = RecordingGameClientObserver()
        val id = StringIdentity(java.util.UUID.randomUUID().toString)
        val client = system.actorOf(Props(new AkkaGameClient(id, server, observer)(ExecutionContext.global)))
        (
          TypedActor(system).typedActorOf(
            props = TypedProps(classOf[GameBot], new GameBotImpl(client, name)),
            name = "akkaGameBot-" + name
          ),
          observer
        )
      }

      // You need this to wait for the server to start listening
      val world0 = Await.result((server ? "WORLD").mapTo[InMemoryWorld], Duration("5 seconds"))

      val numberOfBots = 3
      val botConfigs = (1 to numberOfBots - 1).map(_.toString).map(makeTcpIpBot) ++ List(numberOfBots.toString).map(makeAkkaBot)
      val bots = botConfigs.map(_._1)
      val observers = botConfigs.map(_._2)

      Thread.sleep(3000)

      val world1 = Await.result((server ? "WORLD").mapTo[InMemoryWorld], Duration("5 seconds"))

      world1.things must be size(0)

      bots.foreach { b =>
        b.join()
        Thread.sleep(500)
      }

      Thread.sleep(2000)

      val world2 = Await.result((server ? "WORLD").mapTo[InMemoryWorld], Duration("5 seconds"))
      world2.things must be size(3)

      bots(0).leave()

      Thread.sleep(2000)

      val world3 = Await.result((server ? "WORLD").mapTo[InMemoryWorld], Duration("5 seconds"))
      world3.things must be size(2)

      observers(0).asInstanceOf[RecordingGameClientObserver].observed must be size(3) // bots(0) joins and then bots(1) joins, then bots(2) joins
      observers(1).asInstanceOf[RecordingGameClientObserver].observed must be size(3) // bots(1) joins and then bots(2) joins, then bots(0) leaves
      observers(2).asInstanceOf[RecordingGameClientObserver].observed must be size(2) // bots(2) joins and then bots(0) leaves

      val selfId: Identity = bots(1).selfId.get
      println("bots(1).selfId: " + selfId)
      bots(1).nearby.get must be size(1)
      val nearby = bots(1).nearby.get
      println("nearby: " + nearby)
      val nearest = bots(1).nearest.get
      nearby must be size (1)
      nearby(0) must be equalTo (nearest)
      val targetId = nearest
      println("Target pos: " + bots(1).getPos(targetId))
      bots(1).say("Hi!")
      bots(1).shout("Hi!!")
      bots(1).moveTo(Position(1f, 2f))
      Thread.sleep(500)
      bots(1).getPos(selfId).get must be equalTo (Position(1f, 2f))
      bots(1).follow(targetId)
      bots(1).getPos(selfId) must not be equalTo (Position(1f, 2f))
      bots(1).attack(targetId)

      println(observers(1).asInstanceOf[RecordingGameClientObserver].observed)
      println(observers(2).asInstanceOf[RecordingGameClientObserver].observed)

      def after {
        server ! "STOP"
      }

    }
  }
}
