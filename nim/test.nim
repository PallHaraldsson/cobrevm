import unittest
import macros

import parse

macro bin (xs: varargs[untyped]): untyped =
  var items = newNimNode(nnkBracket)

  for x in xs:

    proc addByte (n: BiggestInt) =
      var node = newNimNode(nnkUInt8Lit, x)
      node.intVal = n
      items.add(node)

    proc addStr (str: string) =
      for i in 0..str.high:
        addByte( BiggestInt(str[i]) )

    proc addInt (n: BiggestInt) =
      proc helper (n: BiggestInt) =
        if n > 0:
          helper(n shr 7)
          addByte(n and 0x7f or 0x80)
      helper(n shr 7)
      addByte(n and 0x7f)

    proc otherwise = items.add(newCall(newIdentNode("uint8"), x))

    case x.kind
    of nnkCharLit..nnkUInt64Lit:
      addByte(x.intVal)
    of nnkStrLit:
      addStr(x.strVal)
    of nnkPrefix:
      if x[0].eqIdent("$"):
        case x[1].kind
        of nnkCharLit..nnkUInt64Lit:
          addInt( x[1].intVal )
        of nnkStrLit:
          addInt( x[1].strVal.len )
          addStr( x[1].strVal )
        else: otherwise()
      else: otherwise()
    else: otherwise()

  return if items.len > 0: prefix(items, "@")
    else: parseExpr("newSeq[uint8](0)")


suite "binary":

  test "basics":
    check bin() == newSeq[uint8](0)
    check bin(0) == @[0u8]
    check bin(1) == @[1u8]
    check bin(127, 128, 129) == @[127u8, 128u8, 129u8]
    check bin('A', 'B', 32) == @[65u8, 66u8, 32u8]
    check bin(2+3) == bin(5)

  test "ints":
    check bin($127) == bin(127)
    check bin($128) == bin(0x81, 0)
    check bin($129) == bin(0x81, 1)
    check bin($0x0808) == bin(0x90, 0x08)

  test "strings":
    check bin("") == bin()
    check bin($"") == bin(0)
    check bin(7, "ab") == bin(7, 'a', 'b')
    check bin($"abcde") == bin(5, "abcde")


suite "parser":

  macro modbin (xs: varargs[untyped]): untyped =
    result = newCall("bin")
    result.add( newLit("Cobre ~2\0") )
    for x in xs: result.add x
    result = newCall("parse", result)

  test "Invalid":
    expect EndOfFileError:
      discard parse( bin() )

    expect EndOfFileError:
      discard parse( bin("Cobre ~2 no nul at the end") )

    expect InvalidModuleError:
      discard parse( bin("Signature\0after signature") )

    expect InvalidModuleError:
      discard parse( bin(" Cobre ~2 \0") )

  test "Empty Module":
    check modbin(0, 0, 0, 0, 0) == Module(
      imports: @[],
      types: @[],
      functions: @[],
      statics: @[],
      blocks: @[ newSeq[Inst]() ]
    )

  test "Small Module":
    let parsed = modbin(1, $"cobre.core", 0, 0, 0, 0)
    let model = Module(
      imports: @["cobre.core"],
      types: @[],
      functions: @[],
      statics: @[],
      blocks: @[ newSeq[Inst]() ]
    )
    check(parsed == model)

  test "FullModule":
    #[ Código culang correspondiente

      import cobre.core {type bin;}
      import cobre.prim {
        type int;
        int add (int, int);
        int mul (int, int);
      }
      import cobre.string {type string;}

      struct A { int? a; int? b; }
      union B { int a; A b; }

      int a = 4;
      int b = 5;
      string str = "Hola!";
      int(int) f = add;

      void main () {
        int c = f(a, b);
        int d;
        if (c) {
          d = f(a, c)
        } else {
          d = b;
        }
      }

      358 bytes, vs 197 el binario
    ]#

    let binary = bin(
      "Cobre ~2", 0,
      3, $"cobre.core", $"cobre.prim", $"cobre.string",
      7, # Types
        1, 1, $"int",
        3, 0,
        4, 2, 1, 1,
        5, 2, 0, 2,
        6,
          2, 0, 0,
          1, 0,
        1, 0, $"bin",
        1, 2, $"string",
      13, # Functions
        1, 1, $"add",
          2, 0, 0, 1, 0,
        1, 1, $"mul",
          2, 0, 0, 1, 0,
        5, 1,
        5, 2,
        6, 2, 0,
        6, 2, 1,
        7, 3, 0,
        7, 3, 1,
        6, 3, 0,
        6, 3, 1,
        2, 0, 0,
        1, 2, $"new_string",
          1, 5, 1, 6,
        8, 4,
      5,
        2, $4,
        2, $5,
        3, $5, "Hola!",
        (16 + 6),
        5, 0,
      11, # Block for #10
        4, 0, #0 a
        4, 1, #1 b
        4, 4, #2 f
        (16 + 12), 0, 1, #3 c
        1, #4 d
        8, 3, 9,
        (16 + 12), 0, 3, #5
        3, 4, 5,
        6, 10,
        3, 4, 1,
        0,
      2, # Static Block
        4, 2,
        5, 3, 0,
    )

    #echo "binary size: ", binary.len, " bytes"

    let parsed = parse(binary)

    let model = Module(
      imports: @["cobre.core", "cobre.prim", "cobre.string"],
      types: @[
        Type(kind: importT, mod_index: 1, name: "int"),
        Type(kind: nullableT, type_index: 0),
        Type(kind: productT, field_types: @[1, 1]),
        Type(kind: sumT, field_types: @[0, 2]),
        Type(kind: funT,
          sig: Signature(
            in_types: @[0,0],
            out_types: @[0]
          )
        ),
        Type(kind: importT, mod_index: 0, name: "bin"),
        Type(kind: importT, mod_index: 2, name: "string"),
      ],
      functions: @[
        Function(kind: importF,
          index: 1,
          name: "add",
          sig: Signature(
            in_types: @[0, 0],
            out_types: @[0]
          )
        ),
        Function(kind: importF,
          index: 1,
          name: "mul",
          sig: Signature(
            in_types: @[0, 0],
            out_types: @[0]
          )
        ),
        Function(kind: boxF, index: 1),
        Function(kind: boxF, index: 2),
        Function(kind: getF, index: 2, field_index: 0),
        Function(kind: getF, index: 2, field_index: 1),
        Function(kind: setF, index: 3, field_index: 0),
        Function(kind: setF, index: 3, field_index: 1),
        Function(kind: getF, index: 3, field_index: 0),
        Function(kind: getF, index: 3, field_index: 1),
        Function(kind: codeF, sig: Signature(
          in_types: @[], out_types: @[]
        )),
        Function(kind: importF,
          index: 2,
          name: "new_string",
          sig: Signature(
            in_types: @[5],
            out_types: @[6]
          )
        ),
        Function(kind: callF, index: 4),
      ],
      statics: @[
        Static(kind: intS, value: 4),
        Static(kind: intS, value: 5),
        Static(kind: binS, bytes: bin("Hola!")),
        Static(kind: nullS, type_index: 6),
        Static(kind: functionS, index: 0),
      ],
      blocks: @[
        @[
          Inst(kind: sgtI, a: 0),
          Inst(kind: sgtI, a: 1),
          Inst(kind: sgtI, a: 4),
          Inst(kind: callI, function_index: 12, arg_indexes: @[0, 1]),
          Inst(kind: varI),
          Inst(kind: nifI, a: 3, b: 9),
          Inst(kind: callI, function_index: 12, arg_indexes: @[0, 3]),
          Inst(kind: setI, a: 4, b: 5),
          Inst(kind: jmpI, a: 10),
          Inst(kind: setI, a: 4, b: 1),
          Inst(kind: endI, arg_indexes: @[]),
        ],
        @[
          Inst(kind: sgtI, a: 2),
          Inst(kind: sstI, a: 3, b: 0),
        ],
      ],
    )

    proc id[T](x: T): T = x

    check(parsed.imports == model.imports)
    check(parsed.types == model.types)
    check(id(parsed.functions) == id(model.functions))
    check(id(parsed.statics) == id(model.statics))
    check(id(parsed.blocks) == id(model.blocks))