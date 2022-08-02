package io.kaitai.struct.translators

import io.kaitai.struct.format._
import io.kaitai.struct.datatype._

import io.kaitai.struct.datatype.DataType._
import io.kaitai.struct.exprlang.Ast
import io.kaitai.struct.exprlang.Ast.expr
import io.kaitai.struct.format.{Identifier, InstanceIdentifier, IoIdentifier, NamedIdentifier, ParentIdentifier, RootIdentifier}
import io.kaitai.struct.languages.RustCompiler
import io.kaitai.struct.{RuntimeConfig, Utils}

class RustTranslator(provider: TypeProvider, config: RuntimeConfig)
  extends BaseTranslator(provider) {

  import RustCompiler._

  override def doByteArrayLiteral(arr: Seq[Byte]): String =
    "&[" + arr.map(x => "%0#2xu8".format(x & 0xff)).mkString(", ") + "].to_vec()"
  override def doByteArrayNonLiteral(elts: Seq[Ast.expr]): String =
    s"pack('C*', ${elts.map(translate).mkString(", ")})"

  override val asciiCharQuoteMap: Map[Char, String] = Map(
    '\t' -> "\\t",
    '\n' -> "\\n",
    '\r' -> "\\r",
    '"' -> "\\\"",
    '\\' -> "\\\\"
  )

  override def strLiteralGenericCC(code: Char): String =
    strLiteralUnicode(code)

  override def strLiteralUnicode(code: Char): String =
    "\\u{%x}".format(code.toInt)

  override def numericBinOp(left: Ast.expr,
                            op: Ast.operator,
                            right: Ast.expr) = {
    (detectType(left), detectType(right), op) match {
      case (_: IntType, _: IntType, Ast.operator.Div) =>
        s"${translate(left)} / ${translate(right)}"
      case (_: IntType, _: IntType, Ast.operator.Mod) =>
        s"${translate(left)} % ${translate(right)}"
      case _ =>
        super.numericBinOp(left, op, right)
    }
  }

  override def doName(s: String) = s match {
    case _ =>
      val found = get_instance(get_top_class(provider.nowClass), InstanceIdentifier(s))
      if (found.isDefined) {
        val userType = found.get.dataTypeComposite match {
          case _: UserType => true
          case _ => false
        }
        if (userType) {
          s"$s(${privateMemberName(IoIdentifier)})?"
        } else {
          s"$s(${privateMemberName(IoIdentifier)})?"
        }
      } else {
        s"$s()"
      }
  }

  def get_top_class(c: ClassSpec): ClassSpec = {
    if (c.isTopLevel) {
      return c
    }
    get_top_class(c.upClass.get)
  }

  def get_instance(cs: ClassSpec, id: Identifier): Option[InstanceSpec] = {
    var found : Option[InstanceSpec] = None;
    // look for instance
    cs.instances.foreach { case (instName, instSpec) =>
      if (instName == id) {
        found = Some(instSpec);
      }
    }
    // look deeper
    if (found.isEmpty) {
      cs.types.foreach {
        case (_, typeSpec) => {
          found = get_instance(typeSpec, id)
          if (found.isDefined) {
            return found;
          }
        }
      }
    }
    return found;
  }

  override def anyField(value: expr, attrName: String): String =
    s"${translate(value)}.${doName(attrName)}"

  override def doLocalName(s: String) = s match {
    case Identifier.ITERATOR => "tmpa"
    case Identifier.ITERATOR2 => "tmpb"
    case Identifier.INDEX => "i"
    case Identifier.IO => s"${RustCompiler.privateMemberName(IoIdentifier)}"
    case Identifier.ROOT => s"${RustCompiler.privateMemberName(RootIdentifier)}.ok_or(KError::MissingRoot)?"
    case Identifier.PARENT =>
      // TODO: How to handle _parent._parent?
      s"${RustCompiler.privateMemberName(ParentIdentifier)}.as_ref().unwrap().peek()"
    case _ => s"self.${doName(s)}"
  }

  override def doInternalName(id: Identifier): String =
    s"${doLocalName(idToStr(id))}"

  override def doEnumByLabel(enumTypeAbs: List[String], label: String): String =
    s"&${RustCompiler.types2class(enumTypeAbs)}::${Utils.upperCamelCase(label)}"

  override def doStrCompareOp(left: Ast.expr, op: Ast.cmpop, right: Ast.expr) = {
    s"${translate(left)}.as_str() ${cmpOp(op)} ${translate(right)}"
  }

  override def doEnumById(enumTypeAbs: List[String], id: String) =
    // Just an integer, without any casts / resolutions - one would have to look up constants manually
    id

  override def arraySubscript(container: expr, idx: expr): String =
    s"${translate(container)}[${translate(idx)} as usize]"

  override def doIfExp(condition: expr, ifTrue: expr, ifFalse: expr): String =
    "if " + translate(condition) +
      " { " + translate(ifTrue) + " } else { " +
      translate(ifFalse) + "}"

  // Predefined methods of various types
  override def strConcat(left: Ast.expr, right: Ast.expr): String =
    "format!(\"{}{}\", " + translate(left) + ", " + translate(right) + ")"

  override def strToInt(s: expr, base: expr): String =
    translate(base) match {
      case "10" =>
        s"${translate(s)}.parse().unwrap()"
      case _ =>
        "panic!(\"Converting from string to int in base {} is unimplemented\"" + translate(
          base
        ) + ")"
    }

  override def enumToInt(v: expr, et: EnumType): String =
    s"usize::from(${translate(v)})"

  override def boolToInt(v: expr): String =
    s"${translate(v)} as i32"

  override def floatToInt(v: expr): String =
    s"${translate(v)} as i32"

  override def intToStr(i: expr, base: expr): String = {
    val baseStr = translate(base)
    baseStr match {
      case "10" =>
        s"${translate(i)}.to_string()"
      case _ =>
        s"base_convert(strval(${translate(i)}), 10, $baseStr)"
    }
  }
  override def bytesToStr(bytesExpr: String, encoding: Ast.expr): String =
    s"decode_string($bytesExpr, ${translate(encoding)})?"

  override def bytesLength(b: Ast.expr): String =
    s"${translate(b)}.len()"
  override def strLength(s: expr): String =
    s"${translate(s)}.len()"
  override def strReverse(s: expr): String =
    s"${translate(s)}.graphemes(true).rev().flat_map(|g| g.chars()).collect()"
  override def strSubstring(s: expr, from: expr, to: expr): String =
    s"${translate(s)}.substring(${translate(from)}, ${translate(to)})"

  override def arrayFirst(a: expr): String =
    s"${translate(a)}.first()"
  override def arrayLast(a: expr): String =
    s"${translate(a)}.last()"
  override def arraySize(a: expr): String =
    s"${translate(a)}.len()"
  override def arrayMin(a: Ast.expr): String =
    s"${translate(a)}.iter().min()"
  override def arrayMax(a: Ast.expr): String =
    s"${translate(a)}.iter().max()"

  override def userTypeField(userType: UserType, value: Ast.expr, attrName: String): String = {
    val code = s"${anyField(value, attrName)}()"
    code
  }
}
