/*---
description: template literals and tagged raw
---*/
var n = 42;
if (`v=${n}` !== "v=42") throw new Error("interpolation failed");
if (`${1}+${2}=${1+2}` !== "1+2=3") throw new Error("multi interp failed");
