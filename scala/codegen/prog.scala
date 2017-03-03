package arnaud.myvm.codegen

import collection.mutable.{ArrayBuffer, Buffer, Map, Set, Stack}
import arnaud.myvm.codegen.{Nodes => ND}

class Import {
  val types: Map[String, String] = Map()
  val procs: Map[String, (String, Int, Int)] = Map()
}

object Predefined {
  case class Function (ins: Seq[String], outs: Seq[String])
  case class Module (procs: Map[String, Function])

  val modules = Map[String, Module](
    "Prelude" -> Module(
      Map(
        "print" -> Function(
          Array("String"),
          Nil
        ),
        "itos" -> Function(
          Array("Int"),
          Array("String")
        ),
        "iadd" -> Function(
          Array("Int", "Int"),
          Array("Int")
        )
      )
    )
  )

  def apply(nm: String): Module = modules(nm)
}

class ProgState () {

  val imports: Map[String, Import] = Map()

  def getimport (nm: String) = {
    imports.get(nm) match {
      case None =>
        val imp = new Import()
        imports(nm) = imp
        imp
      case Some(imp) => imp
    }
  }

  val procs: Map[String, ProcState] = Map()

  val globals: Set[String] = Set()
  val constants: Map[String, Any] = Map()
  var constantCount: Int = 0
  def addConstant(value: Any): RegId = {
    constantCount += 1
    val nm = "$const$" + constantCount
    globals += nm
    constants(nm) = value
    RegId(nm)
  }

  def %% (tp: Node) {
    tp match {
      case ND.ImportType(name, module, field) =>
        getimport(module).types(name) = field
      case ND.ImportProc(name, module, field, ins, outs) =>
        getimport(module).procs(name) = (field, ins, outs)
      case ND.Proc(name, returns, params, body) =>
        val proc = new ProcState(this)
        proc.setParams(params)
        proc.setReturns(returns)
        proc %% body
        proc.code +=  Inst.End
        proc.fixTypes()
        procs(name) = proc
      case ND.Block(nds) =>
        nds foreach (%% _)
      case ND.Nil =>
      case other =>
        val name = other.getClass.getSimpleName
        throw new Exception(s"Node type '$name' is not a top-level node")
    }
  }

  def fixTypes () {
    procs.valuesIterator.foreach (_.fixTypes)
  }

  def compileSexpr(): arnaud.sexpr.Node = {
    import arnaud.sexpr._
    import arnaud.sexpr.Implicits._
    type Node = arnaud.sexpr.Node

    def NBuf(i: Int = 32): Buffer[Node] = new ArrayBuffer[Node](i)

    val selfnd = NBuf(64)
    val constnd = NBuf(16)

    selfnd += "SELF"
    selfnd += ListNode("MAIN", "MAIN")

    val imported = AtomNode("Imports") +:
      imports.map{ case(nm, imp) =>
        val types = imp.types.map{
          case (nm, field) => ListNode(nm, field)
        }
        val procs = imp.procs.map{
          case (nm, (field, ins, outs)) =>
            ListNode(nm, field, ins.toString, outs.toString)
        }
        ListNode(nm,
          new ListNode(AtomNode("Types") +: types.toSeq),
          new ListNode(AtomNode("Procs") +: procs.toSeq)
        )
      }.toSeq

    val procnd = AtomNode("Functions") +:
      procs.map{ case(name, procst) =>
        val codeNode = AtomNode("Code") +: procst.code.map{
          case Inst.Cpy(a, b) => ListNode("cpy", a, b)
          case Inst.Cns(a, b) => ListNode("cns", a, b)
          case Inst.Get(a, b, c) => ListNode("get", a, b, c)
          case Inst.Set(a, b, c) => ListNode("set", a, b, c)
          case Inst.New(a) => ListNode("new", a)

          case Inst.Lbl(l) => ListNode("lbl", l)
          case Inst.Jmp(l) => ListNode("jmp", l)
          case Inst.If (l, a) => ListNode("if" , l, a)
          case Inst.Ifn(l, a) => ListNode("ifn", l, a)

          case Inst.Call(nm, rets, args) =>
            ListNode(nm,
              new ListNode(rets map {new AtomNode(_)}),
              new ListNode(args map {new AtomNode(_)})
            )

          case Inst.End => ListNode("end")
        }
        
        val regsNode = AtomNode("Regs") +: procst.regs.map{
          case Reg(nm, tp) => ListNode(nm, tp)
        }

        val returns = new ListNode(("Out" +: procst.returns) map {new AtomNode(_)})
        val params  = new ListNode(("In" +: procst.params)  map {new AtomNode(_)})

        ListNode(name, regsNode, params, returns, codeNode)
      }.toSeq

    constnd += "Constants"
    globals.foreach{ name =>
      selfnd += ListNode(name, "Any")
    }
    constants.foreach{ case (name, v) =>
      //selfnd += ListNode(name, "Any")
      val tp = v match {
        case _:String => "str"
        case _:Float => "num"
        case _:Double => "num"
        case _:Int => "num"
      }
      constnd += ListNode(name, tp, v.toString)
    }

    val structnd = ListNode("Structs")

    ListNode( imported, structnd, procnd, constnd )
  }

  // Cada Int representa un byte. No se supone que estén por encima de 255
  // Uso Int en vez de Byte porque en Java, Byte tiene signo.
  def compileBinary(): Traversable[Int] = {
    val writer = new BinaryWriter(this)

    writer.writeImports()
    writer.writeTypes()
    writer.writeProcs()

    return writer.buf
  }
}