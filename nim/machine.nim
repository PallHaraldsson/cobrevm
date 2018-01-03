
import sequtils
import strutils
import options

from os import sleep

import sourcemap

type
  SrcPos* = object of RootObj
    file*: Option[string]
    line*: Option[int]
    column*: Option[int]

  Signature* = object
    ins*: seq[Type]
    outs*: seq[Type]

  Product* = ref object of RootObj
    tp*: Type
    fields*: seq[Value]

  ValueKind* = enum nilV, boolV, intV, fltV, strV, binV, productV, functionV
  Value* = object
    case kind*: ValueKind
    of nilV: t*: Type
    of boolV: b*: bool
    of intV: i*: int
    of fltV: f*: float
    of strV: s*: string
    of binV: bytes*: seq[uint8]
    of productV: p*: Product
    of functionV: fn*: Function

  FunctionKind* = enum procF, codeF, applyF
  Function* = ref object of RootObj
    name*: string
    sig*: Signature
    codeinfo*: CodeInfo
    case kind*: FunctionKind
    of procF:
      prc*: proc(args: var seq[Value])
    of codeF:
      code*: seq[Inst]
      regcount*: int
      statics*: seq[Value]
    of applyF: discard

  InstKind* = enum endI, varI, dupI, setI, sgtI, sstI, jmpI, jifI, nifI, anyI, callI
  Inst* = object
    case kind*: InstKind
    of varI: discard
    of endI, callI:
      f*: Function
      args*: seq[int]
      ret*: int
    of setI, sgtI, sstI, dupI, jmpI, jifI, nifI, anyI:
      src*: int
      dest*: int
      inst*: int

  State = object
    f: Function
    pc: int
    regs: seq[Value]
    retpos: int
    counter: int

  TypeKind* = enum nativeT, aliasT, nullableT, productT, sumT, functionT
  Type* = ref object of RootObj
    module*: Module
    name*: string
    case kind*: TypeKind
    of nativeT: discard
    of aliasT, nullableT:
      t*: Type
    of productT, sumT:
      ts*: seq[Type]
    of functionT:
      sig*: Signature

  ItemKind* = enum nilItem, fItem, tItem
  Item* = object
    name*: string
    case kind*: ItemKind
    of fItem: f*: Function
    of tItem: t*: Type
    of nilItem: discard

  ModuleKind* = enum functorM, simpleM
  Module* = ref object
    name*: string
    case kind*: ModuleKind
    of simpleM:
      items*: seq[Item]
    of functorM:
      fn*: proc(arg: Module): Module

  CobreError* = object of Exception
    srcpos*: SrcPos

  RuntimeError* = object of CobreError
  StackOverflowError* = object of RuntimeError
  InfiniteLoopError* = object of RuntimeError

proc cobreRaise*[T](msg: string, srcpos: Srcpos = SrcPos()) =
  var e = newException(T, msg)
  e.srcpos = srcpos
  raise e

var machine_modules* = newSeq[Module]()

proc `$`* (f: Function): string = f.name
proc `$`* (i: Item): string =
  if i.kind == nilItem: return "NoItem"
  $i.kind & "(" & i.name & ", " & (case i.kind
    of fItem: $i.f[]
    of tItem: $i.t[]
    else: ""
  ) & ")"
proc `$`* (m: Module): string =
  if m.isNil: return "nil"
  else: result = "Module(" & m.name & ", " & $m.items & ")"
proc `$`* (t: Type): string =
  if t.isNil: return "nil"
  result = "Type_" & $t.kind & "("
  case t.kind
  of nativeT:
    result &= t.name
  of aliasT, nullableT:
    result &= t.t.name
  of productT, sumT:
    if t.ts.len > 0:
      result &= t.ts[0].name
      for i in 1 .. t.ts.high:
        result &= " " & t.ts[i].name
  of functionT:
    for i in 0 .. t.sig.ins.high:
      result &= t.sig.ins[i].name & " "
    result &= "->"
    for i in 0 .. t.sig.outs.high:
      result &= " " & t.sig.outs[i].name
  result &= ")"
proc `[]=`* (m: var Module, k: string, f: Function) =
  m.items.add(Item(name: k, kind: fItem, f: f))
proc `[]=`* (m: var Module, k: string, t: Type) =
  m.items.add(Item(name: k, kind: tItem, t: t))
proc `[]`* (m: Module, k: string): Item =
  if m.kind != simpleM:
    return Item(kind: nilItem)
  for item in m.items:
    if item.name == k:
      return item
  return Item(kind: nilItem)

proc name* (sig: Signature): string =
  let ins = sig.ins.map(proc (t: Type): string = t.name)
  let outs = sig.outs.map(proc (t: Type): string = t.name)
  "(" & ins.join(" ") & " -> " & outs.join(" ") & ")"

proc newModule* (
  name: string,
  types: seq[(string, Type)] = @[],
  funcs: seq[(string, Function)] = @[],
  ): Module =
  result = Module(kind: simpleM, name: name, items: @[])
  for tpl in types:
    let (nm, tp) = tpl
    if tp.name.isNil:
      tp.name = nm
    result[nm] = tp
  for tpl in funcs:
    let (nm, f) = tpl
    if f.name.isNil:
      f.name = nm
    result[nm] = f
  machine_modules.add(result)

proc findModule* (name: string): Module =
  for module in machine_modules:
    if module.name == name:
      return module
  return nil

var max_instruction_count = 10_000
var max_stack_depth = 16

proc newFunction* (
  name: string = "",
  sig: Signature = Signature(ins: @[], outs: @[]),
  prc: proc(args: var seq[Value])
): Function = Function(name: name, sig: sig, kind: procF, prc: prc)

proc makeCode* (
  f: Function,
  code: seq[Inst],
  statics: seq[Value],
  regcount: int) =
  f.kind = codeF
  f.code = code
  f.statics = statics
  shallow(f.statics) # So that functions can change it
  f.regcount = regcount

proc run* (fn: Function, ins: seq[Value]): seq[Value] =
  var args = ins

  if fn.kind == procF:
    fn.prc(args)
    return args


  # Possible optimization: Instead of accessing top through a pointer,
  # move it to the stack

  var stack = newSeq[State](0)
  template top: untyped = stack[stack.high]

  # ins is var to avoid copying it
  proc pushState (f: Function) =
    if stack.len > max_stack_depth:
      raise newException(StackOverflowError, "Stack size is greater than " & $max_stack_depth)

    # TODO: Make a shared value stack for all the states, to not allocate
    # a seq[Value] in every call

    stack.add( State(f: f) )
    top.regs = newSeq[Value](f.regcount)
    for i, v in args.pairs: top.regs[i] = v


  pushState(fn)

  #echo "Statics: ", fn.statics

  try:
    while stack.len > 0:

      # Faster than top (Barely, really)
      var st_ptr = top.addr
      template st: untyped = st_ptr[]

      var advance = true

      #if st.counter > max_instruction_count:
      #  raise newException(InfiniteLoopError, "Function has executed " & $max_instruction_count & " instructions")

      # xs is var to avoid copying it
      proc getArgs (xs: var seq[int]) =
        args.setLen(xs.len)
        for i in 0 .. xs.high:
          args[i] = st.regs[ xs[i] ]

      let inst_ptr = st.f.code[st.pc].addr
      template inst: untyped = inst_ptr[]
      let oldpc = st.pc

      #sleep(100)
      #echo st.f.name, ":", st.pc , " inst:", inst

      case inst.kind
      of varI: discard # noop
      of setI, dupI:
        st.regs[inst.dest] = st.regs[inst.src]
        #echo "  [", inst.dest, "]:", st.regs[inst.dest]
      of sgtI:
        st.regs[inst.dest] = st.f.statics[inst.src]
        #echo "  [", inst.dest, "]:", st.regs[inst.dest]
      of sstI: st.f.statics[inst.dest] = st.regs[inst.src]
      of jmpI: st.pc = inst.inst
      of jifI:
        if st.regs[inst.src].b:
          st.pc = inst.inst
      of nifI:
        if not st.regs[inst.src].b:
          st.pc = inst.inst
      of anyI:
        if st.regs[inst.src].kind == nilV:
          st.pc = inst.inst
        else:
          st.regs[inst.dest] = st.regs[inst.src]
      of callI:
        getArgs(inst.args)
        case inst.f.kind:
        of procF:
          inst.f.prc(args)
          for i in 0 ..< inst.f.sig.outs.len:
            st.regs[i + inst.ret] = args[i]
        of codeF:
          st.retpos = inst.ret
          pushState(inst.f)
          advance = false # avoid advancing to 2nd instruction
        of applyF:
          let fn = args[0].fn
          st.retpos = inst.ret
          args.delete(0) # Remove the function off the arguments
          pushState(fn)
          advance = false # same
      of endI:
        getArgs(inst.args)
        discard stack.pop
        # From here on, top refers to the parent state

        # Avoid accidentally advancing the parent State, because
        # rst.pc == oldpc is up to luck when the states are not the same,
        # or worse, the parent state may be nil if this was the last state
        advance = false

        if stack.len == 0: result = args
        else:
          top.pc.inc() # Confidently advance parent State
          for i, v in args:
            let ni = i + top.retpos
            top.regs[ni] = v
            #echo "  [", ni, "]:", top.regs[ni]

      if advance and top.pc == oldpc: top.pc.inc()
      #top.counter.inc()
  except Exception:
    var e = getCurrentException()
    e.msg &= "\n"
    proc errline (str: string) =
      e.msg &= str & "\n"
    errline("Machine stack (oldest first):")
    for i in 0 ..< stack.high:
      let st = stack[i]
      # (pc - 1) porque pc se incrementa después del call
      errline("  " & st.f.name & " (" & $(st.pc - 1) & ")")

    if stack.len > 0:
      let st = stack[stack.high]
      errline("> " & st.f.name & " (" & $st.pc & ")")
      errline("Code: ")
      for i, inst in st.f.code.pairs:
        errline("  " & $i & ": " & $inst)
      errline("Regs: ")
      for i, reg in st.regs.pairs:
        errline("  " & $i & ": " & $reg)
    raise e

proc `==`* (a: Value, b: Value): bool =
  if a.kind != b.kind: return false
  return case a.kind
  of nilV: true
  of boolV: a.b == b.b
  of intV: a.i == b.i
  of fltV: a.f == b.f
  of strV: a.s == b.s
  of binV: a.bytes == b.bytes
  of productV: a.p == b.p
  of functionV: a.fn == b.fn

when defined(test):
  include test_machine