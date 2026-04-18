// bench-jit-node.js — Node.js 版对比
function sumN(n) {
  var s = 0, i = 0;
  while (i < n) { s = s + i; i = i + 1; }
  return s;
}
function poly(n) {
  var s = 0, i = 0;
  while (i < n) { s = s + i * i - (i + 1) * 3; i = i + 1; }
  return s;
}
function isPrime(n) {
  if (n < 2) return 0;
  var i = 2;
  while (i * i <= n) { if (n % i === 0) return 0; i = i + 1; }
  return 1;
}
function countPrimes(upTo) {
  var c = 0, i = 2;
  while (i < upTo) { c = c + isPrime(i); i = i + 1; }
  return c;
}
function bench(name, fn, arg) {
  for (var i = 0; i < 3; i++) fn(arg);
  var t0 = Date.now();
  var r;
  for (var i = 0; i < 5; i++) r = fn(arg);
  console.log("  " + name + ": 5 次耗时 " + (Date.now() - t0) + "ms, 结果=" + r);
}
console.log("== Node.js ==");
bench("sumN(1M)    ", sumN, 1000000);
bench("poly(1M)    ", poly, 1000000);
bench("countPrimes ", countPrimes, 5000);
