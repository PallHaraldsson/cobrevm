package arnaud.culang

import arnaud.cobre
import collection.mutable


class CompileError(msg: String, node: Ast.Node) extends Exception (
  if (node.hasSrcpos) s"$msg. At line ${node.line + 1} column ${node.column}" else msg
)

package object compiler {
  implicit class NodeOps (node: Ast.Node) {
    def error(msg: String): Nothing = throw new CompileError(msg, node)
  }

  sealed abstract class Item

  class Program (filename: String) {
    type Module = compiler.Module[this.type]
    type Rutine = compiler.Rutine[this.type]

    val program = new cobre.Program()
    val modules = mutable.Set[Module]()
    val rutines = mutable.Set[Rutine]()
    val types = mutable.Map[String, program.Type]()
    val constants = mutable.Map[String, ConstItem]()

    case class TypeItem (tp: program.Type) extends Item
    case class RutItem (rut: program.Function) extends Item
    case class ConstItem (cns: program.Static, tp: program.Type) extends Item

    case class Proto(ins: Seq[program.Type], outs: Seq[program.Type])

    def Module(name: String, params: Seq[Ast.Expr]) = {
      val mod = new Module(this, name, params)
      modules += mod; mod
    }

    // `object default` es Lazy, pero necesito que los módulos se evalúen
    val default = new Object {
      val intmod = Module("cobre.int", Nil)
      val core = Module("cobre.core", Nil)
      val strmod = Module("cobre.string", Nil)
      val fltmod = Module("cobre.float", Nil)
      val sysmod = Module("cobre.system", Nil)

      val binaryType = core.types("bin")
      val boolType = core.types("bool")
      val intType = intmod.types("int")
      val fltType = fltmod.types("float")
      val strType = strmod.types("string")

      intmod.rutines ++= Map(
        "neg" -> Proto( Array(intType), Array(intType) ),
        "signed" -> Proto( Array(intType), Array(intType) ),
        "add" -> Proto( Array(intType, intType), Array(intType) ),
        "sub" -> Proto( Array(intType, intType), Array(intType) ),
        "mul" -> Proto( Array(intType, intType), Array(intType) ),
        "div" -> Proto( Array(intType, intType), Array(intType) ),
        "eq"  -> Proto( Array(intType, intType), Array(boolType) ),
        "gt"  -> Proto( Array(intType, intType), Array(boolType) ),
        "gte" -> Proto( Array(intType, intType), Array(boolType) ),
        "lt"  -> Proto( Array(intType, intType), Array(boolType) ),
        "lte" -> Proto( Array(intType, intType), Array(boolType) ),
      )

      fltmod.rutines ++= Map(
        "add" -> Proto( Array(fltType, fltType), Array(fltType) ),
        "sub" -> Proto( Array(fltType, fltType), Array(fltType) ),
        "mul" -> Proto( Array(fltType, fltType), Array(fltType) ),
        "div" -> Proto( Array(fltType, fltType), Array(fltType) ),
        "eq"  -> Proto( Array(fltType, fltType), Array(boolType) ),
        "gt"  -> Proto( Array(fltType, fltType), Array(boolType) ),
        "gte" -> Proto( Array(fltType, fltType), Array(boolType) ),
        "lt"  -> Proto( Array(fltType, fltType), Array(boolType) ),
        "lte" -> Proto( Array(fltType, fltType), Array(boolType) ),
        "itof"-> Proto( Array(intType), Array(fltType) ),
        "decimal" -> Proto( Array(intType, intType), Array(fltType) ),
      )

      def ineg = intmod.rutines("neg").get
      def iadd = intmod.rutines("add").get
      def isub = intmod.rutines("sub").get
      def imul = intmod.rutines("mul").get
      def idiv = intmod.rutines("div").get
      def ieq  = intmod.rutines("eq").get
      def igt  = intmod.rutines("gt").get
      def igte = intmod.rutines("gte").get
      def ilt  = intmod.rutines("lt").get
      def ilte = intmod.rutines("lte").get
      def isigned = intmod.rutines("signed").get

      def fadd = fltmod.rutines("add").get
      def fsub = fltmod.rutines("sub").get
      def fmul = fltmod.rutines("mul").get
      def fdiv = fltmod.rutines("div").get
      def feq  = fltmod.rutines("eq").get
      def fgt  = fltmod.rutines("gt").get
      def fgte = fltmod.rutines("gte").get
      def flt  = fltmod.rutines("lt").get
      def flte = fltmod.rutines("lte").get
      def fdecimal = fltmod.rutines("decimal").get

      strmod.rutines ++= Map(
        "new" -> Proto( Array(binaryType), Array(strType) ),
        "concat" -> Proto( Array(strType, strType), Array(strType) ),
        "itos" -> Proto( Array(intType), Array(strType) ),
        "ftos" -> Proto( Array(fltType), Array(strType) ),
      )

      def newstr = strmod.rutines("new").get
      def concat = strmod.rutines("concat").get

      sysmod.rutines ++= Map(
        "print" -> Proto( Array(strType), Nil ),
        "clock" -> Proto( Nil, Array(fltType) )
      )

      val types = Map(
        "int" -> intType,
        "float" -> fltType,
        "bool" -> boolType,
        "string" -> strType
      )

      def apply(nm: String) = types.get(nm) map TypeItem
    }

    object meta {
      import mutable.ArrayBuffer
      import cobre.{meta => Meta}
      import Meta.implicits._

      val srcmap = ArrayBuffer[Meta.Node](
        "source map", Meta.SeqNode("file", filename)
      )
    }

    def get (name: String): Option[Item] = {
      // Todos los items definidos en módulos en el scope
      lazy val items = for (
        mod <- modules if mod.inScope;
        item <- {
          def tp = mod.types.get(name).map(TypeItem)
          def rut = mod.rutines(name).map(RutItem)
          tp orElse rut
        }
      ) yield item

      ( constants.get(name) orElse // Constantes
        rutines. // Rutinas de este módulo
          find(_.name == name).
          map(_.rdef).map(RutItem) orElse
        types.get(name).map(TypeItem) orElse // tipos de este módulo
        items.headOption orElse // rutinas o tipos en otros módulos
        modules.find(_.alias == name) orElse // modulos con el nombre
        default(name) ) // builtins (string, true, null, etc..)
    }

    def %% (node: Ast.Expr): Item = node match {
      case Ast.IntLit(int) =>
        val base = program.IntStatic(int.abs)
        val const = if (int >= 0) base else {
          val const = program.NullStatic(default.intType)
          val reg = program.StaticCode.Sgt(base).reg
          val call = program.StaticCode.Call(default.ineg, Array(reg))
          program.StaticCode.Sst(const, call.regs(0))
          const
        }
        ConstItem(const, default.intType)
      case Ast.FltLit(mag, exp) =>
        val magst = program.IntStatic(mag.abs)
        val expst = program.IntStatic(exp.abs)
        val const = program.NullStatic(default.fltType)

        val magreg = {
          val reg = program.StaticCode.Sgt(magst).reg
          if (mag<0)
            program.StaticCode.Call(default.ineg, Array(reg)).regs(0)
          else reg
        }

        val expreg = {
          val reg = program.StaticCode.Sgt(expst).reg
          if (exp<0)
            program.StaticCode.Call(default.ineg, Array(reg)).regs(0)
          else reg
        }

        val call = program.StaticCode.Call(default.fdecimal, Array(magreg, expreg))
        program.StaticCode.Sst(const, call.regs(0))

        ConstItem(const, default.fltType)
      case Ast.Str(str) =>
        val bytes = str.getBytes("UTF-8")
        val bin = program.BinStatic(
          bytes map (_.asInstanceOf[Int])
        )
        val const = program.NullStatic(default.strType)

        val reg = program.StaticCode.Sgt(bin).reg
        val call = program.StaticCode.Call(default.newstr, Array(reg))
        program.StaticCode.Sst(const, call.regs(0))

        ConstItem(const, default.strType)
      case Ast.Var(name) =>
        get(name) getOrElse node.error(s"$name is undefined")
      case Ast.Field(expr, name) =>
        %%(expr) match {
          case mod: Module =>
            mod.get(name) getOrElse node.error(
              s"$name not found in ${mod.name}"
            )
        }
    }

    def %%! (node: Ast.Expr): program.Static = {
      %%(node) match {
        case ConstItem(const, _) => const
        case TypeItem(tp) => program.TypeStatic(tp)
      }
    }

    def getType (node: Ast.Type): program.Type = %%(node.expr) match {
      case TypeItem(tp) => tp
      case _ => node.expr.error("Not a Type")
    }

    def compile (stmts: Seq[Ast.Toplevel]) {
      // El orden de estas operaciones importa, cada una depende de que
      // las anteriores estén definidas

      object mods {
        val types = mutable.Buffer[(Module, Ast.ImportType)]()
        val ruts  = mutable.Buffer[(Module, Ast.ImportRut)]()

        for (stmt@Ast.Import(names, params, alias, defs) <- stmts) {
          val modname = names mkString "."//"\u001f"
          val hname = names mkString "."
          val module = modules find { module: Module =>
            (module.name == modname) && (module.params == params)
          } match {
            case None =>
              if (defs.size == 0)
                stmt.error(s"Unknown contents of module $hname")
              Module(modname, params)
            case Some(module) =>
              if (defs.size > 0)
                stmt.error(s"Module $hname cannot be redefined")
              module
          }

          if (alias.isEmpty) module.inScope = true
          else module.alias = alias.get

          defs foreach {
            case df: Ast.ImportType => types += ((module, df))
            case df: Ast.ImportRut  => ruts  += ((module, df))
          }
        }
      }

      for (( module, Ast.ImportType(name) ) <- mods.types)
        module.types(name)

      for (stmt@ Ast.Struct(name, fields) <- stmts)
        stmt.error("Structs not yet supported")

      for (( module, node@Ast.ImportRut(name, ins, outs) ) <- mods.ruts)
        module.rutines(name) = Proto(
          ins map {tp: Ast.Type => getType(tp)},
          outs map {tp: Ast.Type => getType(tp)}
        )

      rutines ++= stmts collect {
        case node: Ast.Proc =>
          new Rutine(this, node)
      }

      for (node@Ast.Const(Ast.Type(tpexp), name, expr) <- stmts) {
        val tp = %%(tpexp) match {
          case TypeItem(tp) => tp
          case _ => node.error("Not a type")
        }
        constants(name) = ConstItem(%%!(expr), tp)
      }

      //modules foreach (_.computeParams)

      // Solo compilar las rutinas después de haber creado todos los
      // items de alto nivel
      rutines foreach (_.compile)

      program.StaticCode.End(Nil)

      program.metadata += new cobre.meta.SeqNode(meta.srcmap)
    }
  }

  class Rutine [P <: Program] (val program: P, val node: Ast.Proc) {
    val name = node.name

    val rdef = program.program.FunctionDef(
      for ((tp, _) <- node.params) yield program.getType(tp),
      for (tp <- node.returns) yield program.getType(tp)
    )

    program.program.export(name, rdef)

    import rdef.Reg

    case class RegItem(reg: Reg, tp: program.program.Type) extends Item

    object srcinfo {
      import mutable.ArrayBuffer
      import cobre.{meta => Meta}
      import Meta.SeqNode
      import Meta.implicits._

      // Instruction Index => (Line, Column)
      val insts = mutable.Map[rdef.Inst, (Int, Int)]()

      // Register Index => (Line, Column, Name)
      val vars = mutable.Map[rdef.Reg, (Int, Int, String)]()

      def compile () {
        val buffer = new ArrayBuffer[Meta.Node]
        buffer += "function"
        buffer += rdef.index
        buffer += SeqNode("name", name)
        buffer += SeqNode("line", node.line)
        buffer += SeqNode("column", node.column)

        buffer += new SeqNode(("regs": Meta.Node) +: vars.map{
          case (reg, (line, column, name)) =>
            SeqNode(reg.index, name, line, column)
        }.toSeq)
        buffer += new SeqNode(("code": Meta.Node) +: insts.map{
          case (inst, (line, column)) =>
            SeqNode(inst.index, line, column)
        }.toSeq)
        program.meta.srcmap += new SeqNode(buffer)
      }
    }

    class Scope {
      val map = mutable.Map[String, RegItem]()

      def get (k: String): Option[Item] =
        map.get(k) orElse program.get(k)

      def update (k: String, reg: RegItem) { map(k) = reg }

      class SubScope (val parent: Scope) extends Scope {
        override def get (k: String): Option[Item] =
          map.get(k) orElse parent.get(k)
      }

      def SubScope = new SubScope(this)

      def getRutine (node: Ast.Expr, nargs: Int = -1): program.program.Function =
        %%(node) match {
          case program.RutItem(rut) =>
            if (nargs >= 0 && nargs != rut.ins.size)
              node.error(s"Expected ${rut.ins.size} arguments, found $nargs")
            rut
          case _ => node.error("Not a function")
        }

      def %% (node: Ast.Expr): Item = node match {
        case lit: Ast.Literal => program %% lit
        case Ast.Var(nm) => get(nm) getOrElse node.error(s"$nm is undefined")
        case Ast.Field(expr, field) =>
          %%(expr) match {
            case mod: program.Module =>
              mod.get(field) getOrElse node.error(
                s"$field not found in ${mod.name}"
              )
          }
        case Ast.Call(rutexpr, args) =>
          val rutine = getRutine(rutexpr)
          if (rutine.outs.size < 0) node.error("Expresions cannot be void")
          val call = rdef.Call(rutine, args map (%%!(_).reg))
          val reg = call.regs(0)
          srcinfo.insts(call) = (node.line, node.column)
          RegItem(reg, rutine.outs(0))
        case Ast.Binop(op, _a, _b) =>
          import program.default._
          val a = %%!(_a)
          val b = %%!(_b)
          val (rutine, rtp) = (op, a.tp, b.tp) match {
            case (Ast.Add, `intType`, `intType`) => (iadd, intType)
            case (Ast.Add, `fltType`, `fltType`) => (fadd, fltType)
            case (Ast.Add, `strType`, `strType`) => (concat, strType)
            case (Ast.Sub, `intType`, `intType`) => (isub, intType)
            case (Ast.Sub, `fltType`, `fltType`) => (fsub, fltType)
            case (Ast.Mul, `intType`, `intType`) => (imul, intType)
            case (Ast.Mul, `fltType`, `fltType`) => (fmul, fltType)
            case (Ast.Div, `intType`, `intType`) => (idiv, intType)
            case (Ast.Div, `fltType`, `fltType`) => (fdiv, fltType)
            case (Ast.Gt , `intType`, `intType`) => (igt, boolType)
            case (Ast.Gt , `fltType`, `fltType`) => (fgt, boolType)
            case (Ast.Gte, `intType`, `intType`) => (igte, boolType)
            case (Ast.Gte, `fltType`, `fltType`) => (fgte, boolType)
            case (Ast.Lt , `intType`, `intType`) => (ilt, boolType)
            case (Ast.Lt , `fltType`, `fltType`) => (flt, boolType)
            case (Ast.Lte, `intType`, `intType`) => (ilte, boolType)
            case (Ast.Lte, `fltType`, `fltType`) => (flte, boolType)
            case (Ast.Eq , `intType`, `intType`) => (ieq, boolType)
            case (Ast.Eq , `fltType`, `fltType`) => (feq, boolType)
            case (op, at, bt) => node.error(
              s"Unknown overload for $op"// with ${at} and ${bt}"
            )
          }
          //val reg = rdef.Reg(rtp)
          val call = rdef.Call(rutine, Array(a.reg, b.reg))
          val reg = call.regs(0)
          srcinfo.insts(call) = (node.line, node.column)
          RegItem(reg, rtp)
      }

      def %%! (node: Ast.Expr): RegItem =
        %%(node) match {
          case reg: RegItem => reg
          case program.ConstItem(const, tp) =>
            val sgt = rdef.Sgt(const)
            RegItem(sgt.reg, tp)
          case _ => node.error("Unusable expression")
        }

      def %% (node: Ast.Stmt): Unit = node match {
        case Ast.Decl(Ast.Type(tpexp), parts) =>
          val tp = %%(tpexp) match {
            case program.TypeItem(tp) => tp
            case _ => node.error("Not a type")
          }
          for (decl@ Ast.DeclPart(nm, vl) <- parts) {
            val item = vl match {
              case Some(expr) => %%!(expr)
              case None =>
                val reg = rdef.Var().reg
                RegItem(reg, tp)
            }
            this(nm) = item
            srcinfo.vars(item.reg) = (node.line, node.column, nm)
          }
        case Ast.Call(rutexpr, args) =>
          val rutine = getRutine(rutexpr, args.size)
          val regargs = args map (%%!(_).reg)
          var call = rdef.Call(rutine, regargs)
          srcinfo.insts(call) = (node.line, node.column)
        case Ast.Assign(nm, expr) =>
          val reg = get(nm) match {
            case Some(RegItem(reg, _)) => reg
            case _ => node.error(s"$nm is not a variable")
          }
          val result = %%!(expr)
          val inst = rdef.Set(reg, result.reg)
          srcinfo.insts(inst) = (node.line, node.column)
        case Ast.Multi(_ls, Ast.Call(rutexpr, args)) =>
          val rutine = getRutine(rutexpr, args.size)
          val ls = _ls map {nm => get(nm) match {
            case Some(RegItem(reg, _)) => reg
            case _ => node.error(s"$nm is not a variable")
          } }
          val rs = args map (%%!(_).reg)
          var call = rdef.Call(rutine, rs)
          for (i <- 0 until rutine.outs.size)
            rdef.Set(ls(i), call.regs(i))
          srcinfo.insts(call) = (node.line, node.column)
        case Ast.Multi(_, _) =>
          node.error("Multiple assignment only works with function calls")
        case Ast.Block(stmts) =>
          val scope = SubScope
          stmts foreach (scope %% _)
        case Ast.While(cond, body) =>
          val $start = rdef.Lbl()
          val $end = rdef.Lbl()

          $start.create()
          val $cond = %%!(cond)
          rdef.Nif($end, $cond.reg)

          %%(body)

          rdef.Jmp($start)
          $end.create()
        case Ast.If(cond, body, orelse) =>
          val $else = rdef.Lbl()
          val $end  = rdef.Lbl()

          val $cond = %%!(cond)
          rdef.Nif($else, $cond.reg)

          %%(body)

          rdef.Jmp($end)
          $else.create()
          orelse match {
            case Some(body) => %%(body)
            case None =>
          }
          $end.create()
        case Ast.Return(exprs) =>
          val retcount = Rutine.this.node.returns.size
          if (exprs.size != retcount) node.error(
            s"Expected ${retcount} return values, found ${exprs.size}"
          )
          val args = for (expr <- exprs) yield %%!(expr).reg
          rdef.End(args)
      }
    }

    val topScope = new Scope

    for (i <- 0 until node.params.size)
      topScope(node.params(i)._2) = RegItem(rdef.inregs(i), rdef.ins(i))

    def compile () {
      node.body.stmts map (topScope %% _)
      srcinfo.compile()

      // Implicit return for void functions
      if (node.returns.size == 0)
        rdef.End(Nil)

      //println(rdef.regs mkString " ")
      //for (inst <- rdef.code)
      //  println(inst)
    }
  }

  class Module [P <: Program] (
    val program: P,
    val name: String,
    val params: Seq[Ast.Expr])
    extends Item {

    import program.{Proto, program => prg}

    var alias = ""
    var inScope = false

    lazy val module = prg.Import(name, params.size > 0)

    // Scala no me deja!
    //import prg.{Function, Type}

    object rutines {
      val protos = mutable.Map[String, Proto]()
      val map = mutable.Map[String, prg.Function]()

      def apply (name: String): Option[prg.Function] =
        map get name match {
          case Some(rut) => Some(rut)
          case None => protos get name match {
            case Some(Proto(ins, outs)) =>
              val rut = module.Function(name, ins, outs)
              map(name) = rut
              Some(rut)
            case None => None
          }
        }

      def update (name: String, proto: Proto) { protos(name) = proto }

      def ++= (mp: Map[String, Proto]) { protos ++= mp }
    }

    object types {
      val map = mutable.Map[String, prg.Type]()

      def get (name: String) = map get name
      def apply (name: String) = map get name match {
        case Some(tp) => tp
        case None =>
          val tp = module.Type(name)
          map(name) = tp; tp
      }
    }

    def get (k: String): Option[Item] =
      rutines(k).map(program.RutItem) orElse
      types.get(k).map(program.TypeItem)

    /*def computeParams () {
      for (p <- params) module.params += program %%! p
    }*/
  }

  def compile (prg: Ast.Program, filename: String): cobre.Program = {
    val program = new Program(filename)
    //for (stmt <- prg.stmts) program %% stmt
    program.compile(prg.stmts)
    program.program
  }
}