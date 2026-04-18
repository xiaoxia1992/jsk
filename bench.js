// bench.js — 简单的多场景性能基准
function fib(n) { return n < 2 ? n : fib(n - 1) + fib(n - 2) }

function tight() {
  var s = 0;
  for (var i = 0; i < 1000000; i = i + 1) s = s + i;
  return s;
}

function hotProp() {
  var o = { a: 1, b: 2, c: 3, d: 4 };
  var s = 0;
  for (var i = 0; i < 200000; i = i + 1) s = s + o.a + o.b + o.c + o.d;
  return s;
}

function stringBuild() {
  var s = "";
  for (var i = 0; i < 10000; i = i + 1) s = s + "x";
  return s.length;
}

function bench(name, fn) {
  // warmup
  for (var i = 0; i < 2; i = i + 1) fn();
  var t0 = kjs.ms();
  var r;
  for (var i = 0; i < 5; i = i + 1) r = fn();
  var dt = kjs.ms() - t0;
  console.log("  " + name + ": 5 次耗时 " + dt + "ms, 结果=" + r);
}

console.log("== KJS 基准 ==");
bench("fib(28)     ", function(){ return fib(28) });
bench("tight loop  ", tight);
bench("hot prop    ", hotProp);
bench("string build", stringBuild);
