package cromwell.services.metadata.impl


import akka.actor.SupervisorStrategy.{Decider, Directive, Escalate, Resume}
import akka.actor.{Actor, ActorContext, ActorInitializationException, ActorLogging, ActorRef, OneForOneStrategy, Props}
import com.typesafe.config.{Config, ConfigFactory}
import cromwell.core.Dispatcher.ServiceDispatcher
import cromwell.core.WorkflowId
import cromwell.services.SingletonServicesStore
import cromwell.services.metadata.MetadataService._
import cromwell.services.metadata.impl.MetadataServiceActor._
import cromwell.services.metadata.impl.MetadataSummaryRefreshActor.{MetadataSummaryFailure, MetadataSummarySuccess, SummarizeMetadata}
import cromwell.services.metadata.impl.WriteMetadataActor.CheckPendingWrites
import net.ceedubs.ficus.Ficus._

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

object MetadataServiceActor {

  val MetadataSummaryRefreshInterval: Option[FiniteDuration] = {
    val duration = Duration(ConfigFactory.load().as[Option[String]]("services.MetadataService.config.metadata-summary-refresh-interval").getOrElse("2 seconds"))
    if (duration.isFinite()) Option(duration.asInstanceOf[FiniteDuration]) else None
  }

  def props(serviceConfig: Config, globalConfig: Config) = Props(MetadataServiceActor(serviceConfig, globalConfig)).withDispatcher(ServiceDispatcher)
}

case class MetadataServiceActor(serviceConfig: Config, globalConfig: Config)
  extends Actor with ActorLogging with MetadataDatabaseAccess with SingletonServicesStore {
  
  private val decider: Decider = {
    case _: ActorInitializationException => Escalate
    case _ => Resume
  }
  
  override val supervisorStrategy = new OneForOneStrategy()(decider) {
    override def logFailure(context: ActorContext, child: ActorRef, cause: Throwable, decision: Directive) = {
      val childName = if (child == readActor) "Read" else "Write"
      log.error(s"The $childName Metadata Actor died unexpectedly, metadata events might have been lost. Restarting it...", cause)
    }
  }

  private val summaryActor: Option[ActorRef] = buildSummaryActor

  val readActor = context.actorOf(ReadMetadataActor.props(), "read-metadata-actor")

  val dbFlushRate = serviceConfig.as[Option[FiniteDuration]]("services.MetadataService.db-flush-rate").getOrElse(5 seconds)
  val dbBatchSize = serviceConfig.as[Option[Int]]("services.MetadataService.db-batch-size").getOrElse(200)
  val writeActor = context.actorOf(WriteMetadataActor.props(dbBatchSize, dbFlushRate), "write-metadata-actor")
  implicit val ec = context.dispatcher

  summaryActor foreach { _ => self ! RefreshSummary }

  private def scheduleSummary(): Unit = {
    MetadataSummaryRefreshInterval foreach { context.system.scheduler.scheduleOnce(_, self, RefreshSummary)(context.dispatcher, self) }
  }

  private def buildSummaryActor: Option[ActorRef] = {
    val actor = MetadataSummaryRefreshInterval map {
      _ => context.actorOf(MetadataSummaryRefreshActor.props(), "metadata-summary-actor")
    }
    val message = MetadataSummaryRefreshInterval match {
      case Some(interval) => s"Metadata summary refreshing every $interval."
      case None => "Metadata summary refresh is off."
    }
    log.info(message)
    actor
  }

  private def validateWorkflowId(possibleWorkflowId: WorkflowId, sender: ActorRef): Unit = {
    workflowExistsWithId(possibleWorkflowId.toString) onComplete {
      case Success(true) => sender ! RecognizedWorkflowId
      case Success(false) => sender ! UnrecognizedWorkflowId
      case Failure(e) => sender ! FailedToCheckWorkflowId(new RuntimeException(s"Failed lookup attempt for workflow ID $possibleWorkflowId", e))
    }
  }

  def receive = {
    case action@PutMetadataAction(events) => writeActor forward action
    case action@PutMetadataActionAndRespond(events, replyTo) => writeActor forward action
    case CheckPendingWrites => writeActor forward CheckPendingWrites
    case v: ValidateWorkflowId => validateWorkflowId(v.possibleWorkflowId, sender())
    case action: ReadAction => readActor forward action
    case RefreshSummary => summaryActor foreach { _ ! SummarizeMetadata(sender()) }
    case MetadataSummarySuccess => scheduleSummary()
    case MetadataSummaryFailure(t) =>
      log.error(t, "Error summarizing metadata")
      scheduleSummary()
  }
}
