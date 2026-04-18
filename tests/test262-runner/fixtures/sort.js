/*---
description: sort stability & default comparator
---*/
var a = [3,1,2,10,20];
a.sort();
if (a.join(',') !== '1,10,2,20,3') throw new Error("default sort is stringwise");

var b = [3,1,2,10,20];
b.sort(function(x,y){ return x - y });
if (b.join(',') !== '1,2,3,10,20') throw new Error("numeric sort failed");
