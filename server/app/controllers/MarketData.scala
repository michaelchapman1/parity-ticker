package controllers

import akka.actor.{Actor, ActorRef}
import com.paritytrading.foundation.ASCII
import com.paritytrading.nassau.util.MoldUDP64
import com.paritytrading.parity.net.pmd.{PMD, PMDListener, PMDParser}
import com.paritytrading.parity.book.{Market, MarketListener, OrderBook, Side}
import com.paritytrading.parity.util.{Instrument, Instruments}
import com.typesafe.config.Config
import java.net.InetSocketAddress
import org.jvirtanen.config.Configs
import play.api.libs.json.Json
import scala.collection.JavaConverters._

sealed trait MarketData

case class BBO(
  instrument:  String,
  bidPrice:    Double,
  bidSize:     Double,
  askPrice:    Double,
  askSize:     Double,
  priceDigits: Int,
  sizeDigits:  Int
) extends MarketData

case class Trade(
  instrument:  String,
  price:       Double,
  size:        Double,
  priceDigits: Int,
  sizeDigits:  Int
) extends MarketData

case object MarketDataRequest

class MarketDataReceiver(config: Config, publisher: ActorRef) extends Runnable {

  override def run {
    val multicastInterface = Configs.getNetworkInterface(config, "market-data.multicast-interface")
    val multicastGroup     = Configs.getInetAddress(config, "market-data.multicast-group")
    val multicastPort      = Configs.getPort(config, "market-data.multicast-port")
    val requestAddress     = Configs.getInetAddress(config, "market-data.request-address")
    val requestPort        = Configs.getPort(config, "market-data.request-port")

    val instruments = Instruments.fromConfig(config, "instruments")

    val market = new Market(new MarketListener {

      override def update(book: OrderBook, bbo: Boolean) {

        val bidPrice = book.getBestBidPrice();
        val bidSize  = book.getBidSize(bidPrice);
        val askPrice = book.getBestAskPrice();
        val askSize  = book.getAskSize(askPrice);

        val instrument = instruments.get(book.getInstrument())

        val priceFactor = instrument.getPriceFactor();
        val sizeFactor  = instrument.getSizeFactor();

        publisher ! BBO(
          instrument  = ASCII.unpackLong(book.getInstrument()).trim,
          bidPrice    = bidPrice / priceFactor,
          bidSize     = bidSize / sizeFactor,
          askPrice    = askPrice / priceFactor,
          askSize     = askSize / sizeFactor,
          priceDigits = instrument.getPriceFractionDigits(),
          sizeDigits  = instrument.getSizeFractionDigits()
        )
      }

      override def trade(book: OrderBook, side: Side, price: Long, size: Long) {

        val instrument = instruments.get(book.getInstrument())

        val priceFactor = instrument.getPriceFactor();
        val sizeFactor  = instrument.getSizeFactor();

        publisher ! Trade(
          instrument  = ASCII.unpackLong(book.getInstrument()).trim,
          price       = price / priceFactor,
          size        = size / sizeFactor,
          priceDigits = instrument.getPriceFractionDigits(),
          sizeDigits  = instrument.getSizeFractionDigits()
        )
      }

    })

    instruments.asScala.foreach { instrument =>
      market.open(instrument.asLong())
    }

    MoldUDP64.receive(
      multicastInterface,
      new InetSocketAddress(multicastGroup, multicastPort),
      new InetSocketAddress(requestAddress, requestPort),
      new PMDParser(new PMDListener {

        override def version(message: PMD.Version) = Unit

        override def orderAdded(message: PMD.OrderAdded) {
          market.add(message.instrument, message.orderNumber, side(message.side), message.price, message.quantity)
        }

        override def orderExecuted(message: PMD.OrderExecuted) {
          market.execute(message.orderNumber, message.quantity)
        }

        override def orderCanceled(message: PMD.OrderCanceled) {
          market.cancel(message.orderNumber, message.canceledQuantity)
        }

        def side(side: Byte) = side match {
          case PMD.BUY  => Side.BUY
          case PMD.SELL => Side.SELL
        }
      })
    )
  }
}

class MarketDataPublisher extends Actor {

  var bbos   = Map[String, BBO]()
  var trades = Map[String, Trade]()

  def receive = {
    case bbo: BBO =>
      bbos = bbos.updated(bbo.instrument, bbo)
      context.system.eventStream.publish(bbo)
    case trade: Trade =>
      trades = trades.updated(trade.instrument, trade)
      context.system.eventStream.publish(trade)
    case MarketDataRequest =>
      bbos.values.foreach(sender ! _)
      trades.values.foreach(sender ! _)
  }

}

class MarketDataRelay(publisher: ActorRef, out: ActorRef) extends Actor {

  override def preStart {
    publisher ! MarketDataRequest
    context.system.eventStream.subscribe(self, classOf[MarketData])
  }

  override def postStop {
    context.system.eventStream.unsubscribe(self, classOf[MarketData])
  }

  def receive = {
    case bbo: BBO =>
      out ! Json.obj(
        "instrument"  -> bbo.instrument,
        "bidPrice"    -> bbo.bidPrice,
        "bidSize"     -> bbo.bidSize,
        "askPrice"    -> bbo.askPrice,
        "askSize"     -> bbo.askSize,
        "priceDigits" -> bbo.priceDigits,
        "sizeDigits"  -> bbo.sizeDigits
      )
    case trade: Trade =>
      out ! Json.obj(
        "instrument"  -> trade.instrument,
        "price"       -> trade.price,
        "size"        -> trade.size,
        "priceDigits" -> trade.priceDigits,
        "sizeDigits"  -> trade.sizeDigits
      )
    case _ =>
      Unit
  }

}
