import unittest

suite "Machine":

  let intT = Type(name: "int", kind: nativeT)
  let boolT = Type(name: "int", kind: nativeT)

  proc addf (args: var seq[Value]) =
    let r = args[0].i + args[1].i
    args[0] = Value(kind: intV, i: r)

  proc decf (args: var seq[Value]) =
    args[0] = Value(kind: intV, i: args[0].i - 1)

  proc gtzf (args: var seq[Value]) =
    args[0] = Value(kind: boolV, b: args[0].i > 0)

  let add = newFunction("add", Signature(ins: @[intT, intT], outs: @[intT]), addf)
  let dec = newFunction("dec", Signature(ins: @[intT], outs: @[intT]), decf)
  let gtz = newFunction("gtz", Signature(ins: @[intT], outs: @[boolT]), gtzf)

  var emptyModule = Module( name: "test" )

  var emptyStatics = newSeq[Value](0)
  # This makes nim not deep copy this sequence on assignment or argument passing
  shallow(emptyStatics)

  test "Basics":
    var myfunc = Function(name: "myfunc")
    myfunc.makeCode(
      code = @[
        Inst(kind: setI, src: 0, dest: 2),
        Inst(kind: callI, f: add, args: @[1, 2], ret: 3),
        Inst(kind: endI, args: @[3]),
      ],
      statics = emptyStatics,
      regcount = 4,
    )

    let args = @[
      Value(kind: intV, i: 2),
      Value(kind: intV, i: 3)
    ]

    let expected = @[Value(kind: intV, i: 5)]

    check( myfunc.run(args) == expected )

  test "Control Flow":
    var myfunc = Function(name: "myfunc")
    myfunc.makeCode(
      code = @[
        # while (a > 0) {
        Inst(kind: callI, f: gtz, args: @[0], ret: 2),
        Inst(kind: nifI, src: 2, inst: 10),
        #   a = dec(a);
        Inst(kind: callI, f: dec, args: @[0], ret: 3),
        Inst(kind: setI, src: 3, dest: 0),
        #   b = dec(b);
        Inst(kind: callI, f: dec, args: @[1], ret: 3),
        Inst(kind: setI, src: 3, dest: 1),
        #   if (!gtz(b)) {
        Inst(kind: callI, f: gtz, args: @[1], ret: 2),
        Inst(kind: jifI, src: 2, inst: 9),
        #      return a;
        Inst(kind: endI, args: @[0]),
        #    } }
        Inst(kind: jmpI, inst: 0),
        # return b;
        Inst(kind: endI, args: @[1])
      ],
      statics = emptyStatics,
      regcount = 4,
    )

    let expected = @[Value(kind: intV, i: 3)]
    let result1 = myfunc.run(@[
      Value(kind: intV, i: 3),
      Value(kind: intV, i: 6)
    ])
    check(result1 == expected)
    let result2 = myfunc.run(@[
      Value(kind: intV, i: 6),
      Value(kind: intV, i: 3)
    ])
    check(result2 == expected)

  #[
  # There are no more infinite loop checks
  test "Infinite Loop":
    var myfunc = Function(name: "myfunc")
    myfunc.makeCode(
      code = @[
        # while (a > 0) {
        Inst(kind: callI, f: gtz, args: @[0], ret: 2),
        Inst(kind: nifI, src: 2, inst: 5),
        Inst(kind: setI, src: 0, dest: 1), # x = a
        Inst(kind: setI, src: 1, dest: 0), # a = x
        # }
        Inst(kind: jmpI, inst: 0),
        Inst(kind: endI, args: @[])
      ],
      statics = emptyStatics,
      regcount = 3,
    )

    check( myfunc.run(@[Value(kind: intV, i: 0)]) == newSeq[Value](0) )
    expect InfiniteLoopError:
      discard myfunc.run(@[Value(kind: intV, i: 1)])
  ]#

  test "Recursion":
    var myfunc = Function(name: "myfunc")
    myfunc.makeCode(
      code = @[
        # while (a > 0)
        Inst(kind: callI, f: gtz, args: @[0], ret: 1),
        Inst(kind: nifI, src: 1, inst: 3),
        #   x = myfunc(a);
        Inst(kind: callI, f: myfunc, args: @[0], ret: 0),
        # return x;
        Inst(kind: endI, args: @[])
      ],
      statics = emptyStatics,
      regcount = 2
    )

    check( myfunc.run(@[Value(kind: intV, i: 0)]) == newSeq[Value](0) )
    expect machine.StackOverflowError:
      discard myfunc.run(@[Value(kind: intV, i: 1)])

  test "Statics":
    var module = Module(
      name: "test-statics",
    )
    var statics = @[Value(kind: intV, i: 3)]

    var myfunc = Function(name: "myfunc")
    myfunc.makeCode(
      code = @[
        Inst(kind: sgtI, src: 0, dest: 0),
        Inst(kind: callI, f: dec, args: @[0], ret: 1),
        Inst(kind: sstI, src: 1, dest: 0),
        Inst(kind: endI, args: @[]),
      ],
      statics = statics,
      regcount = 2,
    )
    myfunc.statics.shallowCopy(statics)
    discard myfunc.run(@[])
    let result = statics[0]
    check( result == Value(kind: intV, i: 2) )


