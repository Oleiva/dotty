package tasty

object definitions {

// ------ Names --------------------------------

  trait Name
  trait PossiblySignedName

  enum TermName extends Name with PossiblySignedName {
    case Simple(str: String)
    case Qualified(prefix: TermName, selector: String)              // s"$prefix.$name"

    case DefaultGetter(methodName: TermName, idx: String)           // s"$methodName${"$default$"}${idx+1}"
    case Variant(underlying: TermName, covariant: Boolean)          // s"${if (covariant) "+" else "-"}$underlying"
    case SuperAccessor(underlying: TermName)                        // s"${"super$"}$underlying"
    case ProtectedAccessor(underlying: TermName)                    // s"${"protected$"}$underlying"
    case ProtectedSetter(underlying: TermName)                      // s"${"protected$set"}$underlying"
    case ObjectClass(underlying: TermName)                          // s"$underlying${"$"}"
  }

  case class SignedName(name: TermName, resultSig: TypeName, paramSigs: List[TypeName]) extends PossiblySignedName

  case class TypeName(name: TermName) extends Name

// ------ Positions ---------------------------

  case class Position(firstOffset: Int, lastOffset: Int)

  trait Positioned {
    def pos: Position = ???
  }

// ------ Statements ---------------------------------

  sealed trait TopLevelStatement extends Positioned
  sealed trait Statement extends TopLevelStatement

  case class Package(pkg: Term, body: List[TopLevelStatement]) extends TopLevelStatement

  case class Import(expr: Term, selector: List[ImportSelector]) extends Statement

  enum ImportSelector {
    case Simple(id: Id)
    case Rename(id1: Id, id2: Id)
    case Omit(id1: Id)
  }

  case class Id(name: String) extends Positioned     // untyped ident

// ------ Definitions ---------------------------------

  trait Definition extends Statement {
    def name: Name
    def owner: Definition = ???
  }

  case class ValDef(name: TermName, tpt: TypeTree, rhs: Option[Term], mods: List[Modifier]) extends Definition
  case class DefDef(name: TermName, typeParams: List[TypeDef], paramss: List[List[ValDef]],
                    returnTpt: TypeTree, rhs: Option[Term], mods: List[Modifier]) extends Definition
  case class TypeDef(name: TypeName, rhs: TypeTree, mods: List[Modifier]) extends Definition
  case class ClassDef(name: TypeName, constructor: DefDef, parents: List[Term],
                      self: Option[ValDef], body: List[Statement], mods: List[Modifier]) extends Definition


// ------ Terms ---------------------------------

  /** Trees denoting terms */
  enum Term extends Statement {
    def tpe: Type = ???
    case Ident(name: TermName, override val tpe: Type)
    case Select(prefix: Term, name: PossiblySignedName)
    case Literal(value: Constant)
    case This(id: Option[Id])
    case New(tpt: TypeTree)
    case NamedArg(name: TermName, arg: Term)
    case Apply(fn: Term, args: List[Term])
    case TypeApply(fn: Term, args: List[TypeTree])
    case Super(thiz: Term, mixin: Option[Id])
    case Typed(expr: Term, tpt: TypeTree)
    case Assign(lhs: Term, rhs: Term)
    case Block(stats: List[Statement], expr: Term)
    case Inlined(call: Term, bindings: List[Definition], expr: Term)
    case Lambda(method: Term, tpt: Option[TypeTree])
    case If(cond: Term, thenPart: Term, elsePart: Term)
    case Match(scrutinee: Term, cases: List[CaseDef])
    case Try(body: Term, catches: List[CaseDef], finalizer: Option[Term])
    case Return(expr: Term)
    case Repeated(args: List[Term])
    case SelectOuter(from: Term, levels: Int, target: Type) // can be generated by inlining
  }

  /** Trees denoting types */
  enum TypeTree extends Positioned {
    def tpe: Type = ???
    case Synthetic()
    case Ident(name: TypeName, override val tpe: Type)
    case Select(prefix: Term, name: TypeName)
    case Singleton(ref: Term)
    case Refined(underlying: TypeTree, refinements: List[Definition])
    case Applied(tycon: TypeTree, args: List[TypeTree])
    case TypeBounds(loBound: TypeTree, hiBound: TypeTree)
    case Annotated(tpt: TypeTree, annotation: Term)
    case And(left: TypeTree, right: TypeTree)
    case Or(left: TypeTree, right: TypeTree)
    case ByName(tpt: TypeTree)
  }

  /** Trees denoting patterns */
  enum Pattern extends Positioned {
    def tpe: Type = ???
    case Value(v: Term)
    case Bind(name: TermName, pat: Pattern)
    case Unapply(unapply: Term, implicits: List[Term], pats: List[Pattern])
    case Alternative(pats: List[Pattern])
    case TypeTest(tpt: TypeTree)
    case Wildcard()
  }

  case class CaseDef(pat: Pattern, guard: Option[Term], rhs: Term) extends Positioned

// ------ Types ---------------------------------

  sealed trait Type

  object Type {
    private val PlaceHolder = ConstantType(Constant.Unit)

    case class ConstantType(value: Constant) extends Type
    case class SymRef(sym: Definition, qualifier: Type | NoPrefix = NoPrefix) extends Type
    case class NameRef(name: Name, qualifier: Type | NoPrefix = NoPrefix) extends Type // NoPrefix means: select from _root_
    case class SuperType(thistp: Type, underlying: Type) extends Type
    case class Refinement(underlying: Type, name: Name, tpe: Type | TypeBounds) extends Type
    case class AppliedType(tycon: Type, args: List[Type | TypeBounds]) extends Type
    case class AnnotatedType(underlying: Type, annotation: Term) extends Type
    case class AndType(left: Type, right: Type) extends Type
    case class OrType(left: Type, right: Type) extends Type
    case class ByNameType(underlying: Type) extends Type
    case class ParamRef(binder: LambdaType[_, _, _], idx: Int) extends Type
    case class RecursiveThis(binder: RecursiveType) extends Type

    case class RecursiveType private (private var _underlying: Type) extends Type {
      def underlying = _underlying
    }
    object RecursiveType {
      def apply(underlyingExp: RecursiveType => Type) = {
        val rt = new RecursiveType(PlaceHolder) {}
        rt._underlying = underlyingExp(rt)
        rt
      }
    }

    abstract class LambdaType[ParamName, ParamInfo, This <: LambdaType[ParamName, ParamInfo, This]](
      val companion: LambdaTypeCompanion[ParamName, ParamInfo, This]
    ) extends Type {
      private[Type] var _pinfos: List[ParamInfo]
      private[Type] var _restpe: Type

      def paramNames: List[ParamName]
      def paramInfos: List[ParamInfo] = _pinfos
      def resultType: Type = _restpe
    }

    abstract class LambdaTypeCompanion[ParamName, ParamInfo, This <: LambdaType[ParamName, ParamInfo, This]] {
      def apply(pnames: List[ParamName], ptypes: List[ParamInfo], restpe: Type): This

      def apply(pnames: List[ParamName], ptypesExp: This => List[ParamInfo], restpeExp: This => Type): This = {
        val lambda = apply(pnames, Nil, PlaceHolder)
        lambda._pinfos = ptypesExp(lambda)
        lambda._restpe = restpeExp(lambda)
        lambda
      }
    }

    case class MethodType(paramNames: List[TermName], private[Type] var _pinfos: List[Type], private[Type] var _restpe: Type)
    extends LambdaType[TermName, Type, MethodType](MethodType) {
      def isImplicit = (companion `eq` ImplicitMethodType) || (companion `eq` ErasedImplicitMethodType)
      def isErased = (companion `eq` ErasedMethodType) || (companion `eq` ErasedImplicitMethodType)
    }

    case class PolyType(paramNames: List[TypeName], private[Type] var _pinfos: List[TypeBounds], private[Type] var _restpe: Type)
    extends LambdaType[TypeName, TypeBounds, PolyType](PolyType)

    case class TypeLambda(paramNames: List[TypeName], private[Type] var _pinfos: List[TypeBounds], private[Type] var _restpe: Type)
    extends LambdaType[TypeName, TypeBounds, TypeLambda](TypeLambda)

    object TypeLambda extends LambdaTypeCompanion[TypeName, TypeBounds, TypeLambda]
    object PolyType   extends LambdaTypeCompanion[TypeName, TypeBounds, PolyType]
    object MethodType extends LambdaTypeCompanion[TermName, Type, MethodType]

    class SpecializedMethodTypeCompanion extends LambdaTypeCompanion[TermName, Type, MethodType] { self =>
      def apply(pnames: List[TermName], ptypes: List[Type], restpe: Type): MethodType =
        new MethodType(pnames, ptypes, restpe) { override val companion = self }
    }
    object ImplicitMethodType       extends SpecializedMethodTypeCompanion
    object ErasedMethodType         extends SpecializedMethodTypeCompanion
    object ErasedImplicitMethodType extends SpecializedMethodTypeCompanion

    case class TypeBounds(loBound: Type, hiBound: Type)

    case class NoPrefix()
    object NoPrefix extends NoPrefix
  }

// ------ Modifiers ---------------------------------

  enum Modifier extends Positioned {
    case Private, Protected, Abstract, Final, Sealed, Case, Implicit, Erased, Lazy, Override, Inline,
         Macro,                 // inline method containing toplevel splices
         Static,                // mapped to static Java member
         Object,                // an object or its class (used for a ValDef or a ClassDef, respectively)
         Trait,                 // a trait (used for a ClassDef)
         Local,                 // used in conjunction with Private/private[Type] to mean private[this], proctected[this]
         Synthetic,             // generated by Scala compiler
         Artifact,              // to be tagged Java Synthetic
         Mutable,               // when used on a ValDef: a var
         Label,                 // method generated as a label
         FieldAccessor,         // a getter or setter
         CaseAcessor,           // getter for case class parameter
         Covariant,             // type parameter marked “+”
         Contravariant,         // type parameter marked “-”
         Scala2X,               // Imported from Scala2.x
         DefaultParameterized,  // Method with default parameters
         Stable                 // Method that is assumed to be stable

    case QualifiedPrivate(boundary: Type)
    case QualifiedProtected(boundary: Type)
    case Annotation(tree: Term)
  }

// ------ Constants ---------------------------------

  enum Constant(val value: Any) {
    case Unit                        extends Constant(())
    case Null                        extends Constant(null)
    case Boolean(v: scala.Boolean)   extends Constant(v)
    case Byte(v: scala.Byte)         extends Constant(v)
    case Short(v: scala.Short)       extends Constant(v)
    case Char(v: scala.Char)         extends Constant(v)
    case Int(v: scala.Int)           extends Constant(v)
    case Long(v: scala.Long)         extends Constant(v)
    case Float(v: scala.Float)       extends Constant(v)
    case Double(v: scala.Double)     extends Constant(v)
    case String(v: java.lang.String) extends Constant(v)
    case Class(v: Type)              extends Constant(v)
    case Enum(v: Type)               extends Constant(v)
  }
}
