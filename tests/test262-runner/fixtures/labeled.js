/*---
description: labeled break works across nested loops
---*/
var hits = 0;
outer: for (var i = 0; i < 3; i = i + 1) {
  for (var j = 0; j < 3; j = j + 1) {
    if (j === 1) break outer;
    hits = hits + 1;
  }
}
if (hits !== 1) throw new Error("labeled break failed: " + hits);
