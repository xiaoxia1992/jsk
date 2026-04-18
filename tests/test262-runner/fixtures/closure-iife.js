/*---
description: closure correctly captures loop counter
---*/
function make() {
  var fns = [];
  for (var i = 0; i < 3; i = i + 1) {
    (function(n) { fns.push(function(){ return n }) })(i);
  }
  return fns;
}
var fs = make();
if (fs[0]() !== 0) throw new Error("closure 0 failed");
if (fs[1]() !== 1) throw new Error("closure 1 failed");
if (fs[2]() !== 2) throw new Error("closure 2 failed");
