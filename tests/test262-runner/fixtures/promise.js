/*---
description: Promise.resolve + then (sync)
---*/
var got;
Promise.resolve(7).then(function(v) { got = v * 2 });
if (got !== 14) throw new Error("promise sync chain failed: " + got);
