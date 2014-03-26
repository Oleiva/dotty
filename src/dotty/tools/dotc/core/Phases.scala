package dotty.tools.dotc
package core

import Periods._
import Contexts._
import util.DotClass
import DenotTransformers._
import Denotations._
import config.Printers._
import scala.collection.mutable.{ListBuffer, ArrayBuffer}
import dotty.tools.dotc.transform.TreeTransforms.{TreeTransformer, TreeTransform}
import dotty.tools.dotc.transform.PostTyperTransformers.PostTyperTransformer
import dotty.tools.dotc.transform.TreeTransforms
import TreeTransforms.Separator

trait Phases {
  self: Context =>

  import Phases._

  def phase: Phase = base.phases(period.phaseId)

  def phasesStack: List[Phase] =
    if ((this eq NoContext) || !phase.exists) Nil
    else phase :: outersIterator.dropWhile(_.phase == phase).next.phasesStack

  /** Execute `op` at given phase */
  def atPhase[T](phase: Phase)(op: Context => T): T =
    atPhase(phase.id)(op)

  def atNextPhase[T](op: Context => T): T = atPhase(phase.next)(op)

  def atPhaseNotLaterThan[T](limit: Phase)(op: Context => T): T =
    if (!limit.exists || phase <= limit) op(this) else atPhase(limit)(op)

  def atPhaseNotLaterThanTyper[T](op: Context => T): T =
    atPhaseNotLaterThan(base.typerPhase)(op)
}

object Phases {

  trait PhasesBase {
    this: ContextBase =>

    // drop NoPhase at beginning
    def allPhases = squashedPhases.tail

    object NoPhase extends Phase {
      override def exists = false
      def name = "<no phase>"
      def run(implicit ctx: Context): Unit = unsupported("run")
      def transform(ref: SingleDenotation)(implicit ctx: Context): SingleDenotation = unsupported("transform")
    }

    object SomePhase extends Phase {
      def name = "<some phase>"
      def run(implicit ctx: Context): Unit = unsupported("run")
    }

    /** A sentinel transformer object */
    class TerminalPhase extends DenotTransformer {
      def name = "terminal"
      def run(implicit ctx: Context): Unit = unsupported("run")
      def transform(ref: SingleDenotation)(implicit ctx: Context): SingleDenotation =
        unsupported("transform")
      override def lastPhaseId(implicit ctx: Context) = id
    }

    /** Use the following phases in the order they are given.
     *  The list should never contain NoPhase.
     *  if squashing is enabled, phases in same subgroup will be squashed to single phase.
     */
    def usePhases(phasess: List[List[Phase]], squash: Boolean = false) = {
      phases = (NoPhase :: phasess.flatten ::: new TerminalPhase :: Nil).toArray
      nextDenotTransformerId = new Array[Int](phases.length)
      denotTransformers = new Array[DenotTransformer](phases.length)
      var i = 0
      while (i < phases.length) {
        phases(i).init(this, i)
        i += 1
      }
      var lastTransformerId = i
      while (i > 0) {
        i -= 1
        phases(i) match {
          case transformer: DenotTransformer =>
            lastTransformerId = i
            denotTransformers(i) = transformer
          case _ =>
        }
        nextDenotTransformerId(i) = lastTransformerId
      }

      if (squash) {
        val squashedPhases = ListBuffer[Phase]()
        var postTyperEmmited = false
        var i = 0
        while (i < phasess.length) {
          if (phasess(i).length > 1) {
            assert(phasess(i).forall(x => x.isInstanceOf[TreeTransform]), "Only tree transforms can be squashed")

            val transforms = phasess(i).asInstanceOf[List[TreeTransform]]
            val block =
              if (!postTyperEmmited) {
                postTyperEmmited = true
                new PostTyperTransformer {
                  override def name: String = transformations.map(_.name).mkString("TreeTransform:{", ", ", "}")
                  override protected def transformations: Array[TreeTransform] = transforms.toArray
                }
              } else new TreeTransformer {
                override def name: String = transformations.map(_.name).mkString("TreeTransform:{", ", ", "}")
                override protected def transformations: Array[TreeTransform] = transforms.toArray
              }
            squashedPhases += block
            block.init(this, phasess(i).head.id)
          } else squashedPhases += phasess(i).head
          i += 1
        }
        this.squashedPhases = (NoPhase::squashedPhases.toList :::new TerminalPhase :: Nil).toArray
      } else {
        this.squashedPhases = this.phases
      }

      config.println(s"Phases = ${phases.deep}")
      config.println(s"squashedPhases = ${squashedPhases.deep}")
      config.println(s"nextDenotTransformerId = ${nextDenotTransformerId.deep}")
    }

    def phaseNamed(name: String) = phases.find(_.name == name).getOrElse(NoPhase)

    /** A cache to compute the phase with given name, which
     *  stores the phase as soon as phaseNamed returns something
     *  different from NoPhase.
     */
    private class PhaseCache(name: String) {
      private var myPhase: Phase = NoPhase
      def phase = {
        if (myPhase eq NoPhase) myPhase = phaseNamed(name)
        myPhase
      }
    }

    private val typerCache = new PhaseCache(typerName)
    private val refChecksCache = new PhaseCache(refChecksName)
    private val erasureCache = new PhaseCache(erasureName)
    private val flattenCache = new PhaseCache(flattenName)

    def typerPhase = typerCache.phase
    def refchecksPhase = refChecksCache.phase
    def erasurePhase = erasureCache.phase
    def flattenPhase = flattenCache.phase
  }

  final val typerName = "typer"
  final val refChecksName = "refchecks"
  final val erasureName = "erasure"
  final val flattenName = "flatten"

  abstract class Phase extends DotClass {

    def name: String

    def run(implicit ctx: Context): Unit

    def runOn(units: List[CompilationUnit])(implicit ctx: Context): Unit =
      for (unit <- units) run(ctx.fresh.withPhase(this).withCompilationUnit(unit))

    def description: String = name

    def checkable: Boolean = true

    def exists: Boolean = true

    private var myId: PhaseId = -1
    private var myBase: ContextBase = null
    private var myErasedTypes = false
    private var myFlatClasses = false
    private var myRefChecked = false

    /** The sequence position of this phase in the given context where 0
     * is reserved for NoPhase and the first real phase is at position 1.
     * -1 if the phase is not installed in the context.
     */
    def id = myId

    def erasedTypes = myErasedTypes
    def flatClasses = myFlatClasses
    def refChecked = myRefChecked

    def init(base: ContextBase, id: Int): Unit = {
      if (id >= FirstPhaseId)
        assert(myId == -1, s"phase $this has already been used once; cannot be reused")
      myBase = base
      myId = id
      myErasedTypes = prev.name == erasureName   || prev.erasedTypes
      myFlatClasses = prev.name == flattenName   || prev.flatClasses
      myRefChecked  = prev.name == refChecksName || prev.refChecked
    }

    final def <=(that: Phase)(implicit ctx: Context) =
      exists && id <= that.id

    final def prev: Phase =
      if (id > FirstPhaseId) myBase.phases(id - 1) else myBase.NoPhase

    final def next: Phase =
      if (hasNext) myBase.phases(id + 1) else myBase.NoPhase

    final def hasNext = id >= FirstPhaseId && id + 1 < myBase.phases.length

    final def iterator =
      Iterator.iterate(this)(_.next) takeWhile (_.hasNext)

    override def toString = name
  }
}