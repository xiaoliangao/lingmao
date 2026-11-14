#!/bin/bash
# 原型构建：把 src/ 拼成一个 index.html，双击就能打开，不用联网、不用起服务。
# 每个 js 文件单独一个 <script>：一个文件写错只坏它自己，控制台报错能看出是哪个文件。
# 检查：node check.mjs（构建 + 无头 Chrome 打开每个页面、跑自检）
set -e
SRC="$(dirname "$0")/src"
OUT="${OUT:-$(dirname "$0")/index.html}"
{
cat <<'HEAD'
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>织间服饰 · 供应链</title>
<style>
HEAD
cat "$SRC/style.css"
echo '</style>'
echo '</head>'
echo '<body>'
echo '<div id="app"></div>'
echo '<script>'; cat "$SRC/vendor/vue.global.prod.js"; echo; echo '</script>'
for f in "$SRC"/[0-9]*.js; do
  echo "<script>/* $(basename "$f") */"
  cat "$f"
  echo '</script>'
done
echo '</body>'
echo '</html>'
} > "$OUT"
echo "built $OUT ($(wc -c < "$OUT") bytes)"
