package upickle

import ScalaVersionStubs._
import scala.annotation.StaticAnnotation
import scala.language.experimental.macros

/**
 * Used to annotate either case classes or their fields, telling uPickle
 * to use a custom string as the key for that class/field rather than the
 * default string which is the full-name of that class/field.
 */
class key(s: String) extends StaticAnnotation

/**
 * Implementation of macros used by uPickle to serialize and deserialize
 * case classes automatically. You probably shouldn't need to use these
 * directly, since they are called implicitly when trying to read/write
 * types you don't have a Reader/Writer in scope for.
 */
object Macros {

  class RW(val short: String, val long: String, val actionNames: Seq[String])

  object RW {

    object R extends RW("R", "Reader", Seq("apply"))

    object W extends RW("W", "Writer", Seq("unapply", "unapplySeq"))

  }

  def macroRImpl[T: c0.WeakTypeTag](c0: Context): c0.Expr[Reader[T]] = {
    new Macros{val c: c0.type = c0}.read[T](implicitly[c0.WeakTypeTag[T]])
  }

  def macroWImpl[T: c0.WeakTypeTag](c0: Context): c0.Expr[Writer[T]] = {
    new Macros{val c: c0.type = c0}.write[T](implicitly[c0.WeakTypeTag[T]])
  }

}
abstract class Macros{
  val c: Context
  import Macros._
  import c.universe._
  def read[T: c.WeakTypeTag] = {
    val tpe = weakTypeTag[T].tpe
    println(Console.BLUE + "START " + Console.RESET + tpe)
    if (tpe.typeSymbol.fullName.startsWith("scala."))
      c.abort(c.enclosingPosition, s"this may be an error, can not generate Reader[$tpe <: ${tpe.typeSymbol.fullName}]")
    val res = c.Expr[Reader[T]] {
      picklerFor(tpe, RW.R)(
        _.map(p => q"$p.read": Tree)
          .reduce((a, b) => q"$a orElse $b")
      )
    }
    println(Console.BLUE + "END " + Console.RESET + tpe)
    res
  }

  def write[T: c.WeakTypeTag] = {
    println(Console.BLUE + "START " + Console.RESET + c.enclosingPosition)
    val tpe = weakTypeTag[T].tpe
//    println(Console.RED + "WRITE " + Console.RESET + tpe)
    if (tpe.typeSymbol.fullName.startsWith("scala."))
      c.abort(c.enclosingPosition, s"this may be an error, can not generate Writer[$tpe <: ${tpe.typeSymbol.fullName}]")

    val res = picklerFor(tpe, RW.W) { things =>
      if (things.length == 1) q"upickle.Internal.merge0(${things(0)}.write)"
      else things.map(p => q"$p.write": Tree)
        .reduce((a, b) => q"upickle.Internal.merge($a, $b)")
    }
//    println(Console.GREEN + "TYPECHECK" + Console.RESET)
//    println(res)
    println(Console.BLUE + "END " + Console.RESET + c.enclosingPosition)
    c.Expr[Writer[T]](res)
  }
  /**
   * Generates a pickler for a particular type
   *
   * @param tpe The type we are generating the pickler for
   * @param rw Configuration that determines whether it's a Reader or
   *           a Writer, together with the various names which vary
   *           between those two choices
   * @param treeMaker How to merge the trees of the multiple subpicklers
   *                  into one larger tree
   */
  def picklerFor(tpe: c.Type, rw: RW)
                (treeMaker: Seq[c.Tree] => c.Tree): c.Tree = {
    //    println(Console.CYAN + "picklerFor " + Console.RESET + tpe)


    val memo = collection.mutable.Map.empty[TypeKey, Map[TypeKey, TermName]]
    case class TypeKey(t: c.Type) {
      override def equals(o: Any) = t =:= o.asInstanceOf[TypeKey].t
    }
    def rec(tpe: c.Type, name: TermName, seen: Set[TypeKey]): Map[TypeKey, TermName] = {

      println("rec " + tpe + " " + seen)
      if (seen(TypeKey(tpe))) Map()
      else {
        memo.getOrElseUpdate(TypeKey(tpe), {
          //      println(memo.size)
          val rtpe = typeOf[Reader[Int]] match {
            case TypeRef(a, b, _) => TypeRef(a, b, List(tpe))
          }
          c.inferImplicitValue(rtpe, withMacrosDisabled = true) match {
            case EmptyTree =>
              tpe match{
                case TypeRef(_, cls, args) if cls == definitions.RepeatedParamClass =>
                  rec(args(0), c.fresh[TermName]("t"), seen ++ Set(TypeKey(tpe)))
                case tpe if tpe.typeSymbol.asClass.isTrait =>
                  Map(TypeKey(tpe) -> name) ++ tpe.typeSymbol.asClass.knownDirectSubclasses.flatMap { x =>
                    rec(x.asType.toType, c.fresh[TermName]("t"), seen ++ Set(TypeKey(tpe)))
                  }
                case tpe if tpe.typeSymbol.isModuleClass =>
                  Map(TypeKey(tpe) -> name)
                case _ =>
                  Map(TypeKey(tpe) -> name) ++ getArgSyms(tpe)._2.map(_.typeSignature).flatMap(rec(_, c.fresh[TermName]("t"), seen ++ Set(TypeKey(tpe)))).toSet
              }

            case _ =>
              Map()
          }

        })
      }
    }
    println("A")
    val first = c.fresh[TermName]("t")
    println("B")
    val recTypes = rec(tpe, first, Set())
    println("C")
    val knotName = newTermName("knot" + rw.short)
    println("recTypes " + recTypes)

    val things = recTypes.map { case (TypeKey(tpe), name) =>
      val pick =
        if (tpe.typeSymbol.asClass.isTrait) pickleTrait(tpe, rw)(treeMaker)
        else if (tpe.typeSymbol.isModuleClass) pickleCaseObject(tpe, rw)(treeMaker)
        else pickleClass(tpe, rw)(treeMaker)
      val i = c.fresh[TermName]("i")
      val x = c.fresh[TermName]("x")
      val tree = q"""
        implicit lazy val $name: upickle.${newTypeName(rw.long)}[$tpe] = {
          new upickle.Knot.${newTypeName(rw.short)}[$tpe]($pick)
        }
      """
      (i, tree)
    }
    val res = q"""
      ..${things.map(_._2)}

      $first
    """
    println("RES " + res)
    res
  }

  def pickleTrait(tpe: c.Type, rw: RW)
                 (treeMaker: Seq[c.Tree] => c.Tree): c.universe.Tree = {
    val clsSymbol = tpe.typeSymbol.asClass

    if (!clsSymbol.isSealed) {
      val msg = s"[error] The referenced trait [[${clsSymbol.name}]] must be sealed."
      Console.err.println(msg)
      c.abort(c.enclosingPosition, msg) /* TODO Does not show message. */
    }

    if (clsSymbol.knownDirectSubclasses.isEmpty) {
      val msg = s"The referenced trait [[${clsSymbol.name}]] does not have any sub-classes. This may " +
        "happen due to a limitation of scalac (SI-7046) given that the trait is " +
        "not in the same package. If this is the case, the hierarchy may be " +
        "defined using integer constants."
      Console.err.println(msg)
      c.abort(c.enclosingPosition, msg) /* TODO Does not show message. */
    }

    val subPicklers =
      clsSymbol.knownDirectSubclasses
               .map(subCls => q"implicitly[upickle.${newTypeName(rw.long)}[$subCls]]")
               .toSeq

    val combined = treeMaker(subPicklers)

    q"""upickle.${newTermName(rw.long)}[$tpe]($combined)"""
  }

  def pickleCaseObject(tpe: c.Type, rw: RW)
                      (treeMaker: Seq[c.Tree] => c.Tree) =
  {
    val mod = tpe.typeSymbol.asClass.module

    annotate(tpe)(q"upickle.Internal.${newTermName("Case0"+rw.short)}[$tpe]($mod)")
  }

  /** If there is a sealed base class, annotate the pickled tree in the JSON
    * representation with a class label.
    */
  def annotate(tpe: c.Type)
              (pickler: c.universe.Tree) = {
    val sealedParent = tpe.baseClasses.find(_.asClass.isSealed)
    sealedParent.fold(pickler) { parent =>
      val index = customKey(tpe.typeSymbol).getOrElse(tpe.typeSymbol.fullName)
      q"upickle.Internal.annotate($pickler, $index)"
    }
  }

  /**
   * Get the custom @key annotation from the parameter Symbol if it exists
   */
  def customKey(sym: c.Symbol): Option[String] = {
    sym.annotations
      .find(_.tpe == typeOf[key])
      .flatMap(_.scalaArgs.headOption)
      .map{case Literal(Constant(s)) => s.toString}
  }

  def getArgSyms(tpe: c.Type) = {
    val companion = companionTree(tpe)

    val apply =
      companion.tpe
        .member(newTermName("apply"))
    if (apply == NoSymbol){
      c.abort(
        c.enclosingPosition,
        s"Don't know how to pickle $tpe; it's companion has no `apply` method"
      )
    }

    val argSyms =
      apply.asMethod
        .paramss
        .flatten
    (companion, argSyms)
  }
  def pickleClass(tpe: c.Type, rw: RW)(treeMaker: Seq[c.Tree] => c.Tree) = {

    val (companion, argSyms) = getArgSyms(tpe)

//    println("argSyms " + argSyms.map(_.typeSignature))
    val args = argSyms.map{ p =>
      customKey(p).getOrElse(p.name.toString)
    }

    val rwName = newTermName(s"Case${args.length}${rw.short}")

    val actionName = rw.actionNames
      .map(newTermName(_))
      .find(companion.tpe.member(_) != NoSymbol)
      .getOrElse(c.abort(c.enclosingPosition, "None of the following methods " +
        "were defined: " + rw.actionNames.mkString(" ")))

    val defaults = argSyms.zipWithIndex.map { case (s, i) =>
      val defaultName = newTermName("apply$default$" + (i + 1))
      companion.tpe.member(defaultName) match{
        case NoSymbol => q"null"
        case _ => q"upickle.writeJs($companion.$defaultName)"
      }
    }

    val typeArgs = tpe match {
      case TypeRef(_, _, args) => args
      case _ => c.abort(
        c.enclosingPosition,
        s"Don't know how to pickle type $tpe"
      )
    }

    val pickler =
      if (args.length == 0) // 0-arg case classes are treated like `object`s
        q"upickle.Internal.${newTermName("Case0"+rw.short)}($companion())"
      else if (args.length == 1 && rw == RW.W) // 1-arg case classes need their output wrapped in a Tuple1
        q"upickle.Internal.$rwName(x => $companion.$actionName[..$typeArgs](x).map(Tuple1.apply), Array(..$args), Array(..$defaults)): upickle.${newTypeName(rw.long)}[$tpe]"
      else // Otherwise, reading and writing are kinda identical
        q"upickle.Internal.$rwName($companion.$actionName[..$typeArgs], Array(..$args), Array(..$defaults)): upickle.${newTypeName(rw.long)}[$tpe]"

    annotate(tpe)(pickler)
  }

  def companionTree(tpe: c.Type) = {
    val companionSymbol = tpe.typeSymbol.companionSymbol

    if (companionSymbol == NoSymbol) {
      val clsSymbol = tpe.typeSymbol.asClass
      val msg = "[error] The companion symbol could not be determined for " +
        s"[[${clsSymbol.name}]]. This may be due to a bug in scalac (SI-7567) " +
        "that arises when a case class within a function is pickled. As a " +
        "workaround, move the declaration to the module-level."
      Console.err.println(msg)
      c.abort(c.enclosingPosition, msg) /* TODO Does not show message. */
    }

    val symTab = c.universe.asInstanceOf[reflect.internal.SymbolTable]
    val pre = tpe.asInstanceOf[symTab.Type].prefix.asInstanceOf[Type]
    c.universe.treeBuild.mkAttributedRef(pre, companionSymbol)
  }
}
 
