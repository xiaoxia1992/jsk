// demo-natives.js —— 演示宿主注入的 `kjs` 命名空间

console.log("1) kjs.rand(100) 三次:", kjs.rand(100), kjs.rand(100), kjs.rand(100));

var t0 = kjs.ms();
var sum = 0;
for (var i = 0; i < 100000; i = i + 1) sum = sum + i;
console.log("2) sum =", sum, "  耗时 ms =", kjs.ms() - t0);

// native 抛出的 JS 异常可以被 JS 的 try/catch 接住
try {
  kjs.assert(1 === 2, "one is not two");
} catch (e) {
  console.log("3) 捕获了 native 抛出的异常:", e.name, "-", e.message);
}

// native 可以回调进 JS 函数（双向跨越）
kjs.repeat(3, function(i) {
  console.log("4) tick", i);
});
