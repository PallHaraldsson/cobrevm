package arnaud.culang

object Lexical {
  import fastparse.all._
  val number = {
    sealed abstract class Sign
    case object Positive extends Sign
    case object Negative extends Sign
    def divideTens (n: Double, i: Int): Double =
      if (i < 1) {n} else { divideTens(n/10, i-1) }
    def multiplyTens (n: Double, i: Int): Double =
      if (i < 1) {n} else { multiplyTens(n*10, i-1) }
    val digits = P( CharIn('0' to '9').rep(1).! )
    val sign =
      P(("+"|"-").!.?.map(_ match {
        case Some("+") => Positive
        case Some("-") => Negative
        case None => Positive
        case _ => ???
      }))
    val intpart =
      P(sign ~ digits).map(_ match {
        case (Positive, digits) => digits.toInt
        case (Negative, digits) => -(digits.toInt)
      })
    val realpart =
      P(intpart ~ ("." ~ digits).?).map(_ match {
        case (intpart, None) => intpart.toDouble
        case (intpart, Some(fracpart)) =>
          intpart.toDouble + divideTens(fracpart.toDouble, fracpart.length)
      })
    P( realpart ~ (("e"|"E") ~ sign ~ digits ).?).map(
      _ match {
        case (realpart, None) => realpart
        case (realpart, Some((Positive, expdigits))) =>
          multiplyTens(realpart, expdigits.toInt)
        case (realpart, Some((Negative, expdigits))) =>
          divideTens(realpart, expdigits.toInt)
      }
    ).map(Ast.Num)
  }

  val string = {
    val validChars = P(!("\\" | "\"") ~ AnyChar.!)
    val uescape = P("\\u" ~/ AnyChar.rep(min=4,max=4).!).map( _.toInt.toChar)
    val xescape = P("\\x" ~/ AnyChar.rep(min=2,max=2).!).map( _.toInt.toChar)
    val escape = P("\\" ~ !("u"|"x"|"z") ~ AnyChar.!).map(_ match {
      case "n" => "\n"
      case "t" => "\t"
      case c => c
    })
    P( "\"" ~/ (validChars|uescape|xescape|escape).rep.map(_.mkString) ~/ "\"" ).map(Ast.Str)
  }

  val const = P(
    kw("true").map( _ => Ast.Bool(true)) |
    kw("false").map(_ => Ast.Bool(false)) |
    kw("null").map( _ => Ast.Null)
  )

  val keywords: Set[String] =
    "true false null if else while return continue break goto import void int bool string".
    split(' ').toSet

  val namechar = CharIn('a' to 'z', 'A' to 'Z', '0' to '9', "_")
  val firstchar = CharIn('a' to 'z', 'A' to 'Z', "_")
  val name = P(firstchar ~ namechar.rep).!.filter(!keywords.contains(_))

  def kw (str: String) = P(str ~ !(namechar))

  val ident = name.map(Ast.Id)
  val typename = P(
    name |
    kw("int").map(_ => "Int") |
    kw("bool").map(_ => "Bool") |
    kw("string").map(_ => "String")
  ).map(Ast.Type)

  val lineComment = P("//" ~ CharsWhile(_ != '\n'))
  val multiComment = P("/*" ~ (!("*/") ~ AnyChar).rep ~ "*/")
  //val ws = P(CharsWhile(_.isSpaceChar))
  val ws = P(CharsWhile(" \n\t".toSet))
  val wscomment = P( (ws|lineComment|multiComment).rep )
}

import fastparse.noApi._
object WsApi extends fastparse.WhitespaceApi.Wrapper(Lexical.wscomment)
import WsApi._

import Lexical.{kw => Kw}

object Expressions {

  object Ops {
    import scala.collection.mutable.{Stack, ListMap}

    val precedences = ListMap[Ast.Op,Int]()

    def op (s: String, o: Ast.Op, prec: Int): P[Ast.Op] = {
      precedences(o) = prec
      P(s) map {_ => o}
    }

    val ops = P(
      op(">" , Ast.Gt , 2) |
      op(">=", Ast.Gte, 2) |
      op("==", Ast.Eq , 2) |
      op("!=", Ast.Neq, 2) |
      op("++", Ast.Cat, 3) |
      op("+" , Ast.Add, 3) |
      op("-" , Ast.Sub, 3) |
      op("*" , Ast.Mul, 4) |
      op("/" , Ast.Div, 4)
    )

    def helper (first: Ast.Expr, pairs: Seq[(Ast.Op, Ast.Expr)]): Ast.Expr = {
      val values = new Stack[Ast.Expr]
      val ops = new Stack[Ast.Op]

      def unfold_until (prec: Int) {
        while (
          values.size >= 2 &&
          ops.size > 0 &&
          precedences(ops.head) >= prec
        ) {
          val top = ops.pop()
          val b = values.pop()
          val a = values.pop()
          values.push(Ast.Binop(top, a, b))
        }
      }

      values.push(first)
      for ( (op, value) <- pairs ) {
        unfold_until( precedences(op) )
        ops.push(op)
        values.push(value)
      }
      unfold_until(0)
      values.pop()
    }

    def expr (atom: P[Ast.Expr]): P[Ast.Expr] = {
      P(atom ~ (ops ~ atom).rep).map{
        case (a, bs) => helper(a, bs)
      }
    }
  }

  val variable = P(Lexical.ident) map Ast.Var
  val const = P(Lexical.number | Lexical.const | Lexical.string)
  val call = P(Lexical.ident ~ "(" ~ expr.rep(sep=",") ~ ")") map Ast.Call.tupled

  val inparen = P("(" ~/ expr ~ ")")
  val atom = P(const | call | variable | inparen)
  val expr: P[Ast.Expr] = Ops.expr(atom);
}


object Statements {
  private val declpart = P(Lexical.ident ~ ("=" ~ Expressions.expr).?) map Ast.DeclPart.tupled

  val decl = P(Lexical.typename ~ declpart.rep(sep=",", min=1) ~ ";") map Ast.Decl.tupled
  val assign = P(Lexical.ident ~ "=" ~/ Expressions.expr ~ ";").map(Ast.Assign.tupled)
  val call = Expressions.call ~ ";"

  val block = P("{" ~ stmt.rep ~ "}").map(Ast.Block)

  val cond = P("(" ~ Expressions.expr ~ ")")

  val ifstmt = P(Kw("if") ~/ cond ~ block ~ (Kw("else") ~ block).?).map(Ast.If.tupled)
  val whilestmt = P(Kw("while") ~/ cond ~ block).map(Ast.While.tupled)

  val retstmt = P(Kw("return") ~/ Expressions.expr.rep(sep=",") ~ ";").map(Ast.Return)
  val breakstmt = P(Kw("break") ~ ";").map(_ => Ast.Break)
  val continuestmt = P(Kw("continue") ~ ";").map(_ => Ast.Continue)

  val stmt: P[Ast.Stmt] =
    P(call | assign | decl |
      block | ifstmt | whilestmt |
      retstmt | continuestmt | breakstmt)
}

object Toplevel {
  val moduleName = P(CharIn('a' to 'z', 'A' to 'Z').rep.!)

  private val param = P(Lexical.typename ~ Lexical.ident) map {case (t, i) => (i, t)}
  private val params = P("(" ~ param.rep(sep = ",")  ~ ")")

  private val procType: P[Seq[Ast.Type]] =
    P(Kw("void").map(_ => Nil) | Lexical.typename.rep(sep=",", min=1))

  val proc = P(procType ~/ Lexical.ident ~ params ~ Statements.block)
  .map {case (tps, nm, pms, body) => Ast.Proc(nm, pms, tps, body)}

  val importstmt = P(Kw("import") ~/ Lexical.name ~ ";") map Ast.Import

  val toplevel: P[Ast.Toplevel] = P(importstmt | proc)

  val program: P[Ast.Program] = P(toplevel.rep).map(Ast.Program)
}
