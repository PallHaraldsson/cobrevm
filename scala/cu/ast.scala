package arnaud.culang

object Ast {
  sealed trait Node
  sealed trait Expr extends Node
  sealed trait Stmt extends Node
  sealed trait Toplevel extends Node
  sealed trait Literal extends Expr

  sealed abstract class Op
  case object Add extends Op
  case object Sub extends Op
  case object Mul extends Op
  case object Div extends Op
  case object Gt  extends Op
  case object Gte extends Op
  case object Eq  extends Op
  case object Neq extends Op
  case object Cat extends Op // Debería ser parte de Add

  case class Id (name: String)
  case class Type (name: String)

  case class Num (n: Double) extends Literal
  case class Str (s: String) extends Literal
  case class Bool (b: Boolean) extends Literal
  case object Null extends Literal

  case class Var (name: Id) extends Expr
  case class Binop (op: Op, a: Expr, b: Expr) extends Expr
  case class Call (func: Id, args: Seq[Expr]) extends Expr with Stmt

  case class DeclPart (nm: Id, vl: Option[Expr])
  case class Decl (tp: Type, ps: Seq[DeclPart]) extends Stmt
  case class Assign (nm: Id, vl: Expr) extends Stmt
  case class Block (stmts: Seq[Stmt]) extends Stmt
  case class If (cond: Expr, body: Block, orelse: Option[Block]) extends Stmt
  case class While (cond: Expr, body: Block) extends Stmt

  case class Return (expr: Seq[Expr]) extends Stmt
  case object Break extends Stmt
  case object Continue extends Stmt

  case class Import (module: String) extends Toplevel
  case class Proc (name: Id, params: Seq[(Id, Type)], returns: Seq[Type], body: Block) extends Toplevel

  case class Program (stmts: Seq[Toplevel])
}