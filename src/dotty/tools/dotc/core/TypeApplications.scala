package dotty.tools.dotc
package core

import Types._
import Contexts._
import Symbols._
import Decorators._
import util.Stats._
import util.common._
import Names._
import NameOps._
import Flags._
import StdNames.tpnme
import util.Positions.Position
import config.Printers._
import collection.mutable
import java.util.NoSuchElementException

object TypeApplications {

  /** Assert type is not a TypeBounds instance and return it unchanged */
  val noBounds = (tp: Type) => tp match {
    case tp: TypeBounds => throw new AssertionError("no TypeBounds allowed")
    case _ => tp
  }

  /** If `tp` is a TypeBounds instance return its lower bound else return `tp` */
  val boundsToLo = (tp: Type) => tp match {
    case tp: TypeBounds => tp.lo
    case _ => tp
  }

  /** If `tp` is a TypeBounds instance return its upper bound else return `tp` */
  val boundsToHi = (tp: Type) => tp match {
    case tp: TypeBounds => tp.hi
    case _ => tp
  }
}

import TypeApplications._

/** A decorator that provides methods for modeling type application */
class TypeApplications(val self: Type) extends AnyVal {

  /** The type parameters of this type are:
   *  For a ClassInfo type, the type parameters of its class.
   *  For a typeref referring to a class, the type parameters of the class.
   *  For a typeref referring to a Lambda class, the type parameters of
   *    its right hand side or upper bound.
   *  For a refinement type, the type parameters of its parent, unless the refinement
   *  re-binds the type parameter with a type-alias.
   *  For any other non-singleton type proxy, the type parameters of its underlying type.
   *  For any other type, the empty list.
   */
  final def typeParams(implicit ctx: Context): List[TypeSymbol] = /*>|>*/ track("typeParams") /*<|<*/ {
    self match {
      case tp: ClassInfo =>
        tp.cls.typeParams
      case tp: TypeRef =>
        val tsym = tp.typeSymbol
        if (tsym.isClass) tsym.typeParams
        else if (tsym.isAliasType) tp.underlying.rawTypeParams
        else {
          val lam = LambdaClass(forcing = false)
          if (lam.exists) lam.typeParams else Nil
        }
      case tp: RefinedType =>
        val tparams = tp.parent.typeParams
        tp.refinedInfo match {
          case rinfo: TypeAlias => tparams.filterNot(_.name == tp.refinedName)
          case _ => tparams
        }
      case tp: SingletonType =>
        Nil
      case tp: TypeProxy =>
        tp.underlying.typeParams
      case _ =>
        Nil
    }
  }

  /** The type parameters of the underlying class.
   *  This is like `typeParams`, except for 3 differences.
   *  First, it does not adjust type parameters in refined types. I.e. type arguments
   *  do not remove corresponding type parameters.
   *  Second, it will return Nil for BoundTypes because we might get a NullPointer exception
   *  on PolyParam#underlying otherwise (demonstrated by showClass test).
   *  Third, it won't return abstract higher-kinded type parameters, i.e. the type parameters of
   *  an abstract type are always empty.
   */
  final def rawTypeParams(implicit ctx: Context): List[TypeSymbol] = {
    self match {
      case tp: ClassInfo =>
        tp.cls.typeParams
      case tp: TypeRef =>
        val tsym = tp.typeSymbol
        if (tsym.isClass) tsym.typeParams
        else if (tsym.isAliasType) tp.underlying.rawTypeParams
        else Nil
      case _: BoundType | _: SingletonType =>
        Nil
      case tp: TypeProxy =>
        tp.underlying.rawTypeParams
      case _ =>
        Nil
    }
  }

  /** If type `tp` is equal, aliased-to, or upperbounded-by a type of the form
   *  `LambdaXYZ { ... }`, the class symbol of that type, otherwise NoSymbol.
   *  symbol of that type, otherwise NoSymbol.
   *  @param forcing  if set, might force completion. If not, never forces
   *                  but returns NoSymbol when it would have to otherwise.
   */
  def LambdaClass(forcing: Boolean)(implicit ctx: Context): Symbol = track("LambdaClass") { self.stripTypeVar match {
    case self: TypeRef =>
      val sym = self.symbol
      if (sym.isLambdaTrait) sym
      else if (sym.isClass || sym.isCompleting && !forcing) NoSymbol
      else self.info.LambdaClass(forcing)
    case self: TypeProxy =>
      self.underlying.LambdaClass(forcing)
    case _ =>
      NoSymbol
  }}

  /** Is type `tp` equal, aliased-to, or upperbounded-by a type of the form
   *  `LambdaXYZ { ... }`?
   */
  def isLambda(implicit ctx: Context): Boolean =
    LambdaClass(forcing = true).exists

  /** Same is `isLambda`, except that symbol denotations are not forced
   *  Symbols in completion count as not lambdas.
   */
  def isSafeLambda(implicit ctx: Context): Boolean =
    LambdaClass(forcing = false).exists

  /** Is type `tp` a Lambda with all Arg$ fields fully instantiated? */
  def isInstantiatedLambda(implicit ctx: Context): Boolean =
    isSafeLambda && typeParams.isEmpty

  def isHK(implicit ctx: Context): Boolean = self.dealias match {
    case self: TypeRef =>
      self.info match {
        case TypeBounds(_, hi) => hi.isHK
        case _ => false
      }
    case RefinedType(_, name) => name == tpnme.hkApply || name.isLambdaArgName
    case _ => false
  }

  /** Encode the type resulting from applying this type to given arguments */
  final def appliedTo(args: List[Type])(implicit ctx: Context): Type = /*>|>*/ track("appliedTo") /*<|<*/ {
    def matchParams(tp: Type, tparams: List[TypeSymbol], args: List[Type]): Type = args match {
      case arg :: args1 =>
        if (tparams.isEmpty) {
          println(s"applied type mismatch: $self $args, typeParams = $typeParams, tsym = ${self.typeSymbol.debugString}") // !!! DEBUG
          println(s"precomplete decls = ${self.typeSymbol.unforcedDecls.toList.map(_.denot).mkString("\n  ")}")
        }
        val tparam = tparams.head
        val arg1 =
          if ((tparam is HigherKinded) && !arg.isLambda && arg.typeParams.nonEmpty) {
            println(i"missing eta expansion of $arg")
            arg.EtaExpand
          }
          else arg
        val tp1 = RefinedType(tp, tparam.name, arg.toBounds(tparam))
        matchParams(tp1, tparams.tail, args1)
      case nil => tp
    }

    /** Instantiate type `tp` with `args`.
     *  @param original  The original type for which we compute the type parameters
     *                   This makes a difference for refinement types, because
     *                   refinements bind type parameters and thereby remove them
     *                   from `typeParams`.
     */
    def instantiate(tp: Type, original: Type): Type = tp match {
      case tp: TypeRef =>
        val tsym = tp.symbol
        if (tsym.isAliasType) tp.underlying.appliedTo(args)
        else {
          val safeTypeParams =
            if (tsym.isClass || !tp.typeSymbol.isCompleting) original.typeParams
            else {
              ctx.warning(i"encountered F-bounded higher-kinded type parameters for $tsym; assuming they are invariant")
              defn.LambdaTrait(args map alwaysZero).typeParams // @@@ can we force?
            }
          matchParams(tp, safeTypeParams, args)
        }
      case tp: RefinedType =>
        val redux = tp.EtaReduce
        if (redux.exists) redux.appliedTo(args) // Rewrite ([HK$0] => C[HK$0])(T)   to   C[T]
        else tp.derivedRefinedType(
          instantiate(tp.parent, original),
          tp.refinedName,
          tp.refinedInfo)
      case tp: TypeProxy =>
        instantiate(tp.underlying, original)
      case tp: PolyType =>
        tp.instantiate(args)
      case ErrorType =>
        tp
    }

    def isHK(tp: Type): Boolean = tp match {
      case tp: TypeRef =>
        val sym = tp.symbol
        if (sym.isClass) sym.isLambdaTrait
        else !sym.isAliasType || isHK(tp.info)
      case tp: TypeProxy => isHK(tp.underlying)
      case _ => false
    }

    if (args.isEmpty || ctx.erasedTypes) self
    else {
      val res = instantiate(self, self)
      if (isHK(res)) TypeRef(res, tpnme.hkApply) else res
    }
  }

  /** Simplify a fully instantiated type of the form `LambdaX{... type Apply = T } # Apply` to `T`.
   */
  def simplifyApply(implicit ctx: Context): Type = self match {
    case self @ TypeRef(prefix, tpnme.hkApply) if prefix.isInstantiatedLambda =>
      prefix.member(tpnme.hkApply).info match {
        case TypeAlias(alias) => alias
        case _ => self
      }
    case _ => self
  }

  final def appliedTo(arg: Type)(implicit ctx: Context): Type = appliedTo(arg :: Nil)
  final def appliedTo(arg1: Type, arg2: Type)(implicit ctx: Context): Type = appliedTo(arg1 :: arg2 :: Nil)

  /** Turn this type, which is used as an argument for
   *  type parameter `tparam`, into a TypeBounds RHS
   */
  final def toBounds(tparam: Symbol)(implicit ctx: Context): TypeBounds = self match {
    case self: TypeBounds => // this can happen for wildcard args
      self
    case _ =>
      val v = tparam.variance
      if (v > 0 && !(tparam is Local) && !(tparam is ExpandedTypeParam)) TypeBounds.upper(self)
      else if (v < 0 && !(tparam is Local) && !(tparam is ExpandedTypeParam)) TypeBounds.lower(self)
      else TypeAlias(self, v)
  }

  /** The type arguments of this type's base type instance wrt.`base`.
   *  Existential types in arguments are returned as TypeBounds instances.
   */
  final def baseArgInfos(base: Symbol)(implicit ctx: Context): List[Type] =
    if (self derivesFrom base)
      base.typeParams map (param => self.member(param.name).info.argInfo(param))
    else
      Nil

  /** The type arguments of this type's base type instance wrt.`base`.
   *  Existential types in arguments are disallowed.
   */
  final def baseArgTypes(base: Symbol)(implicit ctx: Context): List[Type] =
    baseArgInfos(base) mapConserve noBounds

  /** The type arguments of this type's base type instance wrt.`base`.
   *  Existential types in arguments are approximated by their lower bound.
   */
  final def baseArgTypesLo(base: Symbol)(implicit ctx: Context): List[Type] =
    baseArgInfos(base) mapConserve boundsToLo

  /** The type arguments of this type's base type instance wrt.`base`.
   *  Existential types in arguments are approximated by their upper bound.
   */
  final def baseArgTypesHi(base: Symbol)(implicit ctx: Context): List[Type] =
    baseArgInfos(base) mapConserve boundsToHi

  /** The first type argument of the base type instance wrt `base` of this type */
  final def firstBaseArgInfo(base: Symbol)(implicit ctx: Context): Type = base.typeParams match {
    case param :: _ if self derivesFrom base =>
      self.member(param.name).info.argInfo(param)
    case _ =>
      NoType
  }

  /** The base type including all type arguments and applicable refinements
   *  of this type. Refinements are applicable if they refine a member of
   *  the parent type which furthermore is not a name-mangled type parameter.
   *  Existential types in arguments are returned as TypeBounds instances.
   */
  final def baseTypeWithArgs(base: Symbol)(implicit ctx: Context): Type = ctx.traceIndented(s"btwa ${self.show} wrt $base", core, show = true) {
    def default = self.baseTypeRef(base).appliedTo(baseArgInfos(base))
    self match {
      case tp: TypeRef =>
        tp.info match {
          case TypeBounds(_, hi) => hi.baseTypeWithArgs(base)
          case _ => default
        }
      case tp @ RefinedType(parent, name) if !tp.member(name).symbol.is(ExpandedTypeParam) =>
        tp.wrapIfMember(parent.baseTypeWithArgs(base))
      case tp: TermRef =>
        tp.underlying.baseTypeWithArgs(base)
      case AndType(tp1, tp2) =>
        tp1.baseTypeWithArgs(base) & tp2.baseTypeWithArgs(base)
      case OrType(tp1, tp2) =>
        tp1.baseTypeWithArgs(base) | tp2.baseTypeWithArgs(base)
      case _ =>
        default
    }
  }

  /** Translate a type of the form From[T] to To[T], keep other types as they are.
   *  `from` and `to` must be static classes, both with one type parameter, and the same variance.
   *  Do the same for by name types => From[T] and => To[T]
   */
  def translateParameterized(from: ClassSymbol, to: ClassSymbol)(implicit ctx: Context): Type = self match {
    case self @ ExprType(tp) =>
      self.derivedExprType(tp.translateParameterized(from, to))
    case _ =>
      if (self.derivesFrom(from))
        if (ctx.erasedTypes) to.typeRef
        else RefinedType(to.typeRef, to.typeParams.head.name, self.member(from.typeParams.head.name).info)
      else self
  }

  /** If this is repeated parameter type, its underlying Seq type,
   *  or, if isJava is true, Array type, else the type itself.
   */
  def underlyingIfRepeated(isJava: Boolean)(implicit ctx: Context): Type =
    if (self.isRepeatedParam) {
      val seqClass = if (isJava) defn.ArrayClass else defn.SeqClass
      translateParameterized(defn.RepeatedParamClass, seqClass)
    }
    else self

  /** If this is an encoding of a (partially) applied type, return its arguments,
   *  otherwise return Nil.
   *  Existential types in arguments are returned as TypeBounds instances.
   *  @param interpolate   See argInfo
   */
  final def argInfos(interpolate: Boolean)(implicit ctx: Context): List[Type] = {
    var tparams: List[TypeSymbol] = null
    def recur(tp: Type, refineCount: Int): mutable.ListBuffer[Type] = tp.stripTypeVar match {
      case tp @ RefinedType(tycon, name) =>
        val buf = recur(tycon, refineCount + 1)
        if (buf == null) null
        else {
          if (tparams == null) tparams = tycon.typeParams
          if (buf.size < tparams.length) {
            val tparam = tparams(buf.size)
            if (name == tparam.name) buf += tp.refinedInfo.argInfo(tparam, interpolate)
            else null
          } else null
        }
      case _ =>
        if (refineCount == 0) null
        else new mutable.ListBuffer[Type]
    }
    val buf = recur(self, 0)
    if (buf == null) Nil else buf.toList
  }

  final def argInfos(implicit ctx: Context): List[Type] = argInfos(interpolate = true)

  /** Argument types where existential types in arguments are disallowed */
  def argTypes(implicit ctx: Context) = argInfos mapConserve noBounds

  /** Argument types where existential types in arguments are approximated by their lower bound */
  def argTypesLo(implicit ctx: Context) = argInfos mapConserve boundsToLo

  /** Argument types where existential types in arguments are approximated by their upper bound  */
  def argTypesHi(implicit ctx: Context) = argInfos mapConserve boundsToHi

  /** The core type without any type arguments.
   *  @param `typeArgs` must be the type arguments of this type.
   */
  final def withoutArgs(typeArgs: List[Type]): Type = typeArgs match {
    case _ :: typeArgs1 =>
      val RefinedType(tycon, _) = self
      tycon.withoutArgs(typeArgs1)
    case nil =>
      self
  }

  /** If this is the image of a type argument to type parameter `tparam`,
   *  recover the type argument, otherwise NoType.
   *  @param interpolate   If true, replace type bounds as arguments corresponding to
   *                       variant type parameters by their dominating element. I.e. an argument
   *
   *                           T <: U
   *
   *                       for a covariant type-parameter becomes U, and an argument
   *
   *                           T >: L
   *
   *                       for a contravariant type-parameter becomes L.
   */
  final def argInfo(tparam: Symbol, interpolate: Boolean = true)(implicit ctx: Context): Type = self match {
    case self: TypeAlias => self.alias
    case TypeBounds(lo, hi) =>
      if (interpolate) {
        val v = tparam.variance
        if (v > 0 && (lo isRef defn.NothingClass)) hi
        else if (v < 0 && (hi isRef defn.AnyClass)) lo
        else self
      }
      else self
    case _ =>
      NoType
  }

  /** The element type of a sequence or array */
  def elemType(implicit ctx: Context): Type = self match {
    case defn.ArrayType(elemtp) => elemtp
    case JavaArrayType(elemtp) => elemtp
    case _ => firstBaseArgInfo(defn.SeqClass)
  }

  def containsRefinedThis(target: Type)(implicit ctx: Context): Boolean = {
    def recur(tp: Type): Boolean = tp.stripTypeVar match {
      case RefinedThis(tp) =>
        tp eq target
      case tp: NamedType =>
        if (tp.symbol.isClass) !tp.symbol.isStatic && recur(tp.prefix)
        else tp.info match {
          case TypeAlias(alias) => recur(alias)
          case _ => recur(tp.prefix)
        }
      case tp: RefinedType =>
        recur(tp.refinedInfo) || recur(tp.parent)
      case tp: TypeBounds =>
        recur(tp.lo) || recur(tp.hi)
      case tp: AnnotatedType =>
        recur(tp.underlying)
      case tp: AndOrType =>
        recur(tp.tp1) || recur(tp.tp2)
      case _ =>
        false
    }
    recur(self)
  }

  /** The typed lambda abstraction of this type `T` relative to `boundSyms`.
   *  This is:
   *
   *      LambdaXYZ{ type Apply = subst(T) }
   *
   *  where XYZ reflets that variances of the bound symbols and
   *  `subst` is a substitution that replaces every bound symbol sym_i by
   *  `this.Arg$i`.
   *
   *  TypeBounds are lambda abstracting by lambda abstracting their upper bound.
   *
   *  @param cycleParanoid   If `true` don't force denotation of a TypeRef unless
   *                         its name matches one of `boundSyms`. Needed to avoid cycles
   *                         involving F-boundes hk-types when reading Scala2 collection classes
   *                         with new hk-scheme.
   */
  def LambdaAbstract(boundSyms: List[Symbol], cycleParanoid: Boolean = false)(implicit ctx: Context): Type = {
    def expand(tp: Type) = {
      val lambda = defn.LambdaTrait(boundSyms.map(_.variance))
      val substitutedRHS = (rt: RefinedType) => {
        val argRefs = boundSyms.indices.toList.map(i =>
          RefinedThis(rt).select(tpnme.LambdaArgName(i)))
        val substituted =
          if (cycleParanoid) new ctx.SafeSubstMap(boundSyms, argRefs).apply(tp)
          else tp.subst(boundSyms, argRefs)
        substituted.bounds.withVariance(1)
      }
      RefinedType(lambda.typeRef, tpnme.hkApply, substitutedRHS)
    }
    self match {
      case self @ TypeBounds(lo, hi) =>
        self.derivedTypeBounds(lo, expand(TypeBounds.upper(hi)))
      case _ =>
        expand(self)
    }
  }

  /** Convert a type constructor `TC` with type parameters `T1, ..., Tn` to
   *
   *     LambdaXYZ { Apply = TC[$hkArg$0, ..., $hkArg$n] }
   *
   *  where XYZ is a corresponds to the variances of the type parameters.
   */
  def EtaExpand(implicit ctx: Context): Type = {
    val tparams = typeParams
    self.appliedTo(tparams map (_.typeRef)).LambdaAbstract(tparams)
      //.ensuring(res => res.EtaReduce =:= self, s"res = $res, core = ${res.EtaReduce}, self = $self, hc = ${res.hashCode}")
  }

  /** Eta expand if `bound` is a type lambda */
  def EtaExpandIfLambda(bound: Type)(implicit ctx: Context): Type =
    if (bound.isLambda && self.typeSymbol.isClass && typeParams.nonEmpty && !isLambda) EtaExpand
    else self

  /** If `self` is an eta expansion of type T, return T, otherwise NoType */
  def EtaReduce(implicit ctx: Context): Type = {
    def etaCore(tp: Type, tparams: List[Symbol]): Type = tparams match {
      case Nil => tp
      case tparam :: otherParams =>
        tp match {
          case tp: RefinedType =>
            tp.refinedInfo match {
              case TypeAlias(TypeRef(RefinedThis(rt), rname))
              if (rname == tparam.name) && (rt eq self) =>
                etaCore(tp.parent, otherParams)
              case _ =>
                NoType
            }
          case _ =>
            NoType
        }
    }
    self match {
      case self @ RefinedType(parent, tpnme.hkApply) =>
        val lc = parent.LambdaClass(forcing = false)
        self.refinedInfo match {
          case TypeAlias(alias) if lc.exists => etaCore(alias, lc.typeParams.reverse)
          case _ => NoType
        }
      case _ => NoType
    }
  }

  /** Test whether this type has a base type of the form `B[T1, ..., Bn]` where
   *  the type parameters of `B` match one-by-one the variances of `tparams`,
   *  and where the lambda abstracted type
   *
   *     LambdaXYZ { type Apply = B[$hkArg$0, ..., $hkArg$n] }
   *               { type $hkArg$0 = T1; ...; type $hkArg$n = Tn }
   *
   *  satisfies predicate `p`. Try base types in the order of their occurrence in `baseClasses`.
   *  A type parameter matches a variance V if it has V as its variance or if V == 0.
   *  @param classBounds  A hint to bound the search. Only types that derive from one of the
   *                      classes in classBounds are considered.
   */
  def testLifted(tparams: List[Symbol], p: Type => Boolean, classBounds: List[ClassSymbol])(implicit ctx: Context): Boolean = {
    def tryLift(bcs: List[ClassSymbol]): Boolean = bcs match {
      case bc :: bcs1 =>
        val tp = self.baseTypeWithArgs(bc)
        val targs = tp.argInfos
        val tycon = tp.withoutArgs(targs)
        def variancesMatch(param1: Symbol, param2: Symbol) =
          param2.variance == param2.variance || param2.variance == 0
        if (classBounds.exists(tycon.derivesFrom(_)) &&
            tycon.typeParams.corresponds(tparams)(variancesMatch)) {
          val expanded = tycon.EtaExpand
          val lifted = (expanded /: targs) { (partialInst, targ) =>
            val tparam = partialInst.typeParams.head
            RefinedType(partialInst, tparam.name, targ.bounds.withVariance(tparam.variance))
          }
          ctx.traceIndented(i"eta lifting $self --> $lifted", hk) {
            p(lifted) || tryLift(bcs1)
          }
        }
        else tryLift(bcs1)
      case nil =>
        false
    }
    try { // temporary, to avoid type mismatches in applications. Should come back to this
          // when subtyping is rewritten to account for new hk-scheme.
      if (tparams.isEmpty) false
      else if (typeParams.nonEmpty) p(EtaExpand) || classBounds.nonEmpty && tryLift(self.baseClasses)
      else classBounds.nonEmpty && tryLift(self.baseClasses)
    } catch {
      case ex: NoSuchElementException => false
    }
  }
}
