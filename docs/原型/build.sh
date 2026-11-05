#!/bin/bash
# 原型构建：把 src/ 里的样式和脚本拼成一个 index.html（双击就能打开，不用起服务）
# 用法：build.sh [dev]  dev = 用 Vue 开发版，能看到警告
set -e
SRC="$(dirname "$0")/src"
OUT="${OUT:-$(dirname "$0")/index.html}"
VUE=vue.global.prod.js
[ "$1" = dev ] && VUE=vue.global.js
{
cat <<HEAD
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>SCM 原型 · 服装供应链</title>
<meta name="description" content="SCM 一期可点击原型：假数据，刷新恢复。对应 docs/PRD.md 第 6 章。">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Barlow:wght@400;500;600&display=swap">
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/element-plus@2/dist/index.css">
<script src="https://cdn.jsdelivr.net/npm/vue@3/dist/$VUE"></script>
<script src="https://cdn.jsdelivr.net/npm/element-plus@2/dist/index.full.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/element-plus@2/dist/locale/zh-cn.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/@element-plus/icons-vue@2/dist/index.iife.min.js"></script>
<style>
HEAD
cat "$SRC/tokens.css"
echo
cat "$SRC/style.css"
cat <<MID
</style>
</head>
<body>
<div id="app"><p style="padding:40px;color:#888">原型加载中……要联网（Vue、Element Plus 从 jsDelivr 加载）。</p></div>
<script>
MID
for f in "$SRC"/0*.js; do cat "$f"; echo; done
cat <<TAIL
</script>
</body>
</html>
TAIL
} > "$OUT"
echo "built $OUT ($(wc -c < "$OUT") bytes, vue=$VUE)"
