package org.cvogt.compossible

import collection.immutable.ListMap
import scala.language.experimental.macros
import scala.language.dynamics
import scala.reflect.macros.whitebox.{Context => WhiteboxContext}
import scala.reflect.macros.blackbox.{Context => BlackboxContext}

// Extensible records for Scala based on intersection types
object RecordCompletion{
  import scala.language.implicitConversions
  /** import for IntelliJ code completion
      Instead of whitebox macros selectDynamic member resolution,
      switches to structural type record member resolution, which
      uses reflection and leads to corresponding warnings.
      Suggestion: Use this during development and remove for production. */
  implicit def unpack[T](record: Record[T]): T = macro RecordBlackboxMacros.unpackMacro[T]  
}
trait RecordFactory extends Dynamic{
  /** [whitebox] Create a record from a case class */
  def from(obj: Any): Record[AnyRef]
    = macro RecordWhiteboxMacros.fromCaseClassMacro
  /** [whitebox] Create a record from a named arguments list */
  def applyDynamicNamed[T <: AnyRef](method: String)(keyValues: T*): Record[AnyRef] = macro RecordWhiteboxMacros.createManyMacro
}
/** [whitebox] Create a record from a named arguments list 
    (same as Record.named but allows renaming) */
object RecordNamed extends RecordFactory

/** [blackbox] Create a record from a structural refinement type (new { ... })*/
object Record extends RecordFactory{
  def apply[V <: AnyRef](struct: V): Record[V]
    = macro RecordBlackboxMacros.createMacro[V]

  implicit class RecordMethods[T <: AnyRef](val record: Record[T]){
    val values = record.values
    /** [whitebox] Convert Record into a tuple */
    def toTuple: Product
      = macro RecordWhiteboxMacros.tupleMacro    

    /** like structural upcasting but removes the values of the lost fields from memory */
    def select[S >: T <: AnyRef]: Record[S] = macro RecordBlackboxMacros.selectMacro[S]

    def to[S]: S = macro RecordBlackboxMacros.toMacro[S]

    /** Combine two records into one.
        The combined one will have the keys of both. */
    def &[O <: AnyRef](other: Record[O])
      = new Record[T with O](values ++ other.values)

    def With[O <: AnyRef](other: Record[O])
      = new Record[T with O](values ++ other.values)

    def apply[K <: String](select: select[K]): Record[AnyRef]
      = macro RecordWhiteboxMacros.selectMacro[K]

    /** select columns */
    // def map[E](f: T => E)

  }
}

// Record is co-variant for structural upcasting
// For memory efficient conversion use .select instead
class Record[+T <: AnyRef](
  val values: Map[String, Any],
  val struct: Any = null // FIXME
) extends Dynamic{
  override def toString = "Record("+values.toString+")"

  def selectDynamic[K <: String](key: K): Any
    = macro RecordWhiteboxMacros.lookupMacro[K]

  def applyDynamic[K <: String](key: K)(value:Any): Record[AnyRef]
    = macro RecordWhiteboxMacros.appendFieldMacro[K]

  // TODO: make typesafe with macros
  def applyDynamicNamed(method: String)(keyValues: (String, Any)*): Record[T]
    = macro RecordBlackboxMacros.copyMacro[T]

  // somehow conflicted with applyDynamic, etc.
  //def updateDynamic[K <: String](key: K)(value:Any): Record[T]
  //  = new Record[T](values ++ Map(key -> value))

  //def updateDynamic[K <: String](key: K)(value:Any): Any = macro Record.createMacro3[K]
}
trait RecordMacroHelpers extends MacroHelpers{
  val c: BlackboxContext
  import c.universe._

  /** new record from key values pairs */ 
  protected def newRecord(tpe: Tree, keyValues: Seq[Tree], n: Any = null)
    = q"""new Record[$tpe](Map(..$keyValues))"""

  /** lookup record values */
  protected def lookup(record: Tree, key: Tree, valueType: Type)
    = q"$record.values($key).asInstanceOf[$valueType]"

  /** Collects all definition symbols from the the refinement bodies
      of the involved types.
      E.g. Seq(def name: String, def age:Int)
      for  {def name: String} with {def age: Int}
      
      Warning: not tail recursive, could blow the stack */
  protected final def collectDefs(tpe: Type, seq: Seq[Symbol] = Seq()): Seq[Symbol] = {
    tpe match {
      case RefinedType(Seq(tpe),scope) => collectDefs(tpe,seq++scope.toSeq)
      case RefinedType(Seq(),scope) => scope.toSeq
      case RefinedType(tpes,scope) if scope.isEmpty => tpes.map(collectDefs(_)).reduce(_ ++ _)
      case other => seq
    }
  }

  /** map from field names to types */
  protected def typesByKey(tpe: Type): Map[String, Type]
    = ListMap(
        collectDefs(tpe).map{
          case s:MethodSymbol =>
            (s.name.decodedName.toString, s.returnType)
        }: _*
      )

  protected def createRecord(_keyValues: (Tree, Tree)*)
    = {
      val keyValues: Seq[(String,Tree)] = _keyValues match {
        case Seq((Literal(Constant("apply")),q"new {..$fields}")) =>
          fields.map{
            case q"def $key = $value" => (key.decodedName.toString,value)
            case q"val $key = $value" => (key.decodedName.toString,value)
          }
        case _ => _keyValues.map{case (Literal(Constant(key: String)),v) => (key, v)}

      }
      val (types, data) = keyValues.map{
        case (key,value) => (
          q"def ${TermName(key)}: ${value.tpe.widen}",
          q"${key} -> ${value}"
        )
      }.unzip
      newRecord(
        tq"AnyRef{..$types}",
        data
      )
    }
}
class RecordBlackboxMacros(val c: BlackboxContext) extends RecordMacroHelpers{
  import c.universe._

  //def createStructuralMacro[K](struct: Tree)
  //  = createRecord((Literal(Constant("apply")), struct))

  def createMacro[K](struct: Tree)
    = {
      println(showRaw(struct))
      createRecord((Literal(Constant("apply")), struct))
    }

  def unpackMacro[T:c.WeakTypeTag](record: Tree): Tree = {
    //q"org.cvogt.compossible.RecordLookup($record)"
    val fields = typesByKey(firstTypeArg(record)).map{
      case(key, tpe) => q"def ${TermName(key)} = $record.values($key).asInstanceOf[$tpe]"
    }
    q"new{..$fields}"
  }

  def copyMacro[T:c.WeakTypeTag](method: Tree)(keyValues: Tree*) = {
    method match { case Literal(Constant("copy")) => }
    //val values == keyValues.map{(kfoldLeft(q"", $keyValues.toMap
    q"""new Record[${c.weakTypeTag[T]}](
      values=${c.prefix}.values ++ Map(..$keyValues),
      struct=${c.prefix}.struct
    )"""
  }

  def selectMacro[K:c.WeakTypeTag]
    = {
      val tbk = typesByKey(firstTypeArg(c.prefix.tree))

      val selectedKeyValues = typesByKey(c.weakTypeTag[K].tpe)
      selectedKeyValues.foreach{
        case (key, tpe) => assert(tpe == tbk(key))
      }
      val selectedTypes = selectedKeyValues.keys.toSeq

      val defs = selectedTypes zip selectedTypes.map(tbk) map {
        case (key, tpe) => q"def ${TermName(key)}: ${tpe}"
      }
      newRecord(
        tq"AnyRef{..$defs}",
        selectedTypes.map{
          key => q"$key -> ${c.prefix}.values($key)"
        }
      )
    }

  def toMacro[K:c.WeakTypeTag]
    = {
      println(c.weakTypeTag[K])
      val tbk = typesByKey(firstTypeArg(c.prefix.tree))

      c.weakTypeTag[K].tpe match {
        case tpe if isCaseClass(tpe) =>
          val caseClassfields = caseClassFieldsTypes(tpe).map(_._1)
          val accessors = caseClassfields.map{
            case key => lookup(c.prefix.tree,Literal(Constant(key)),tbk(key))
          }
          q"""new $tpe(..$accessors)"""
      }
    }
}

case class RecordLookup(r: Record[_ <: AnyRef]) extends Dynamic{
  def selectDynamic[T](key: String) = r.values(key).asInstanceOf[T]
}
class RecordWhiteboxMacros(val c: WhiteboxContext) extends RecordMacroHelpers{
  import c.universe._


  def createMacro[K](key: Tree)(value: Tree)
    = createRecord((key, value))

  def selectMacro[K <: String:c.WeakTypeTag]
                 (select: Tree)
    = {
      val selectedTypes = splitRefinedTypes(c.weakTypeTag[K].tpe).map{
        case ConstantType(Constant(key: String)) => key
      }
      println(selectedTypes)

      val tbk = typesByKey(firstTypeArg(c.prefix.tree))
      val defs = selectedTypes zip selectedTypes.map(tbk) map {
        case (key, tpe) => q"def ${TermName(key)}: ${tpe}"
      }
      newRecord(
        tq"AnyRef{..$defs}",
        selectedTypes.map{
          key => q"$key -> ${c.prefix}.values($key)"
        }
      )
    }


  def appendFieldMacro[K <: String:c.WeakTypeTag](key: Tree)(value: Tree)
    = {
    q"""${c.prefix.tree} & ${createRecord((key, value))}"""
  }

  def createManyMacro(method: Tree)(keyValues: Tree*)
    = createRecord(keyValues.map(splitTreePair):_*)

  def lookupMacro[K <: String:c.WeakTypeTag](key: Tree)
    = {
      //println(firstTypeArg(c.prefix.tree))
      //println(collectScopes(firstTypeArg(c.prefix.tree)))
      //println(key)
      val valueType = 
        typesByKey(firstTypeArg(c.prefix.tree))
          .get(key match {case Literal(Constant(key:String)) => key})//.tpe match {case ConstantType(Constant(key: String)) => key})
          .getOrElse{
            error(s"""Record has no key .${key}""")
            ???
          }
      lookup(c.prefix.tree, key, valueType)
    }
/*
  def valueMacro: c.Expr[Any]
    = {
      import c.universe._
      val recordTypeArg = c.prefix.actualType.widen.typeArgs.head

      def splitTypes(t: Type): Seq[Type] = t match {
        case RefinedType(types,scope) => types.map(splitTypes(_)).flatten
        case t => Seq(t)
      }
      val keyValuePairTypes: Seq[Type] = splitTypes(recordTypeArg)
      if(keyValuePairTypes.size >1){
        c.error(
          c.enclosingPosition,
          ".value can only be called on single-element Records"
        )
      }

      val Seq(ConstantType(key:Constant),v) = keyValuePairTypes.head.typeArgs
      c.Expr[Any](q"""${c.prefix}.values($key).asInstanceOf[$v]""")
    }

  def extractMacro[K <: String:c.WeakTypeTag]
    (k: c.Expr[K]): c.Expr[Any]
    = {
      val recordTypeArg = c.prefix.actualType.widen.typeArgs.head

      def splitTypes(t: Type): Seq[Type] = t match {
        case RefinedType(types,scope) => types.map(splitTypes(_)).flatten
        case t => Seq(t)
      }
      val keyValuePairTypes: Seq[Type] = splitTypes(recordTypeArg)

      val keyValueMap = keyValuePairTypes.map{
        t =>
          val args = t.typeArgs
          (args(0),args(1))
      }.toMap

      val keyString = k.tree match{
        case Literal(Constant("apply")) => c.error(
          c.enclosingPosition, 
          "Error: You are trying to use \"apply\" as Record key or call a Record's apply method. Both are prohibited."
        )
        case Literal(Constant(s)) => s
        case _ => c.error(c.enclosingPosition, "Only string literals are allows as keys, not: "+k.tree)
      }
      val kt = c.weakTypeTag[K]
      val v = keyValueMap.get(k.tree.tpe).getOrElse{
        c.error(
          c.enclosingPosition,
          s"""Record has no key .$keyString"""
        )
        ???
      }
      c.Expr[Any](q"""new Record[(${k.tree.tpe},$v)](Map(${k.tree} -> ${c.prefix}.values(${k.tree}).asInstanceOf[$v]))""")
    }
*/
  def tupleMacro
    = {
      val accessors = 
        typesByKey(firstTypeArg(c.prefix.tree)).map{
            case (key,valueType) => 
              lookup(q"${c.prefix.tree}.record",Literal(Constant(key)),valueType)
          }
      q"""(..$accessors)"""
    }

  def fromCaseClassMacro(obj: Tree)
    = {
      val tpe = obj.tpe.widen.dealias
      assert(isCaseClass(tpe))

      val names = caseClassFieldsTypes(tpe)

      val keyValues = caseClassFieldsTypes(tpe).map{
        case (name,tpe) => (
          q"""${Constant(name)} -> ${obj}.${TermName(name)}"""
        )
      }

      val defs = names.map{
        case (name,tpe) => (
          q"""def ${TermName(name)}: $tpe"""
        )
      }

      newRecord(tq"{..$defs}", keyValues)
    }
}
