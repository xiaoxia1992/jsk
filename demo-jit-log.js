// demo-jit-log.js — 用来演示 JIT 日志。
// 建议配合环境变量跑：
//   KJS_JIT_LOG=1     ./kjs demo-jit-log.js    ← 只看里程碑
//   KJS_JIT_LOG=trace ./kjs demo-jit-log.js    ← 每次调用的倒计时
//   KJS_JIT=off       ./kjs demo-jit-log.js    ← 关掉 JIT 做对照

function square(x) {          // 纯算术 → 能被 JIT
  return x * x;
}

function loud(msg) {          // 含 LOAD_GLOBAL / CALL → 不能被 JIT
  console.log(msg);
  return msg;
}

console.log("--- 调用 square 5 次（阈值默认 3） ---");
var r = 0;
for (var i = 0; i < 5; i = i + 1) {
  r = square(i);
}
console.log("square 跑了 5 次，最后结果：" + r);

console.log("--- 调用 loud 2 次（不合格，应该被拒） ---");
loud("hello");
loud("world");

console.log("--- 重复调用 square 1000 次 ---");
var s = 0;
for (var i = 0; i < 1000; i = i + 1) s = s + square(i);
console.log("square 累计跑了 1005 次，和 = " + s);
