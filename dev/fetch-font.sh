#!/usr/bin/env bash
# 下载导出 PDF 用的中文字体（架构 §11.1）到 dev/fonts/（不进 git）。本机跑应用、跑集成测试之前执行一次；
# deploy/build-package.sh 打包时也调它，再把字体拷进应用镜像。已经下好、校验对的不再下。
#
# 字体是 Noto Sans SC 的常规体和粗体：Google 发布的思源黑体（同一套字形，SIL OFL 授权，可以随软件分发，授权书 OFL.txt 一起放）。
# 要 TrueType 轮廓、固定粗细的版本：openhtmltopdf 只认 TrueType（Adobe 发的思源黑体 OTF 是 CFF 轮廓），
# 可变字体它只用默认那一档（Noto Sans SC 的默认是最细的 Thin，印出来发虚）。这两个文件是 Google Fonts 按粗细切好的，
# 地址里带版本号（v41），内容不会变；下完再比对 sha256，上游换了文件这里会报错，不会悄悄换字体
set -euo pipefail

cd "$(dirname "$0")"
DIR=fonts
FILES=(
  "NotoSansSC-Regular.ttf|https://fonts.gstatic.com/s/notosanssc/v41/k3kCo84MPvpLmixcA63oeAL7Iqp5IZJF9bmaG9_FnYw.ttf|450625c8d46ab3df97b7904ded955ec2746d17ec76740cb1e91d1ba63a0f89af"
  "NotoSansSC-Bold.ttf|https://fonts.gstatic.com/s/notosanssc/v41/k3kCo84MPvpLmixcA63oeAL7Iqp5IZJF9bmaGzjCnYw.ttf|0066a522a1ac007c1d72bc4fccb114f80ff7294641c78cead9715bd14d43b9ea"
  "OFL.txt|https://raw.githubusercontent.com/google/fonts/2894aab31764f10f29c421bdfd2340d3b382d384/ofl/notosanssc/OFL.txt|1c05c68c34f9708415aada51f17e1b0092d2cea709bf4a94cd38114f9e73d7d9"
)

sha256() {
  if command -v sha256sum > /dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

mkdir -p "$DIR"
for entry in "${FILES[@]}"; do
  IFS='|' read -r name url sum <<< "$entry"
  if [ -f "$DIR/$name" ] && [ "$(sha256 "$DIR/$name")" = "$sum" ]; then
    continue
  fi
  echo "下载 $name ..."
  curl -fSL --retry 3 --max-time 600 -o "$DIR/$name.part" "$url"
  actual=$(sha256 "$DIR/$name.part")
  if [ "$actual" != "$sum" ]; then
    rm -f "$DIR/$name.part"
    echo "$name 校验不对：应该是 $sum，下到的是 $actual" >&2
    exit 1
  fi
  mv "$DIR/$name.part" "$DIR/$name"
done
echo "字体在 dev/$DIR/：$(ls "$DIR" | tr '\n' ' ')"
