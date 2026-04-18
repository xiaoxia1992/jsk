// Copyright (C) 2017 Vadim Zverev. All rights reserved.
// This code is governed by the BSD license found in the LICENSE file.
/*---
description: KJS in-tree smoke case - addition
info: exercises numeric + string addition
---*/
if (1 + 2 !== 3) throw new Error("num add failed");
if ('a' + 1 !== 'a1') throw new Error("mixed add failed");
if ([1,2,3].length !== 3) throw new Error("array length failed");
