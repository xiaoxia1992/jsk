// demo.js —— 随便改，保存后重新跑命令即可
function fib(n) {
  return n < 2 ? n : fib(n - 1) + fib(n - 2);
}

const nums = [1, 2, 3, 4, 5];
const squares = nums.map(x => x * x);
const sum = squares.reduce((a, b) => a + b, 0);

console.log("fib(10)      =", fib(10));
console.log("squares      =", squares.join(", "));
console.log("sum of sq    =", sum);
console.log("template str =", `1+2=${1 + 2}, pi≈${Math.PI.toFixed(3)}`);

// 对象 + 原型
function Point(x, y) { this.x = x; this.y = y; }
Point.prototype.len = function() { return Math.sqrt(this.x*this.x + this.y*this.y); };
console.log("len(3,4)     =", new Point(3, 4).len());

// Map / Set / Promise
const m = new Map(); m.set("a", 1); m.set("b", 2);
console.log("map.get('a') =", m.get("a"), "size =", m.size);

let r;
Promise.resolve(21).then(v => r = v * 2);
console.log("promise      =", r);

// 正则
console.log("regex match  =", "hello world".match(/(\w+) (\w+)/)[2]);

// try/catch
try { throw new Error("boom"); } catch (e) { console.log("caught       =", e.message); }
