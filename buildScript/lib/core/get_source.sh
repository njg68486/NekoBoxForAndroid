#!/bin/bash
set -e

source "buildScript/init/env.sh"
ENV_NB4A=1
source "buildScript/lib/core/get_source_env.sh"
pushd ..

####

if [ ! -d "sing-box" ]; then
  git clone --no-checkout https://github.com/starifly/sing-box.git
fi
pushd sing-box
git checkout "$COMMIT_SING_BOX"
popd

####

if [ ! -d "libneko" ]; then
  git clone --no-checkout https://github.com/starifly/libneko.git
fi
pushd libneko
git checkout "$COMMIT_LIBNEKO"
popd

####

# 闪连魔改 anytls 外挂私有仓库 (go.mod replace github.com/anytls/sing-anytls => ../../sing-anytls-shanlian)
if [ ! -d "sing-anytls-shanlian" ]; then
  git clone "https://x-access-token:${GH_PAT}@github.com/oh5uosnvh/sing-anytls-shanlian.git"
fi
pushd sing-anytls-shanlian
git checkout "$COMMIT_ANYTLS_SHANLIAN"
popd

####

# viewTurbo 魔改 SS 外挂私有仓库 (go.mod replace github.com/sagernet/sing-shadowsocks2 => ../../sing-shadowsocks2-vt)
if [ ! -d "sing-shadowsocks2-vt" ]; then
  git clone "https://x-access-token:${GH_PAT}@github.com/oh5uosnvh/sing-shadowsocks2-vt.git"
fi
pushd sing-shadowsocks2-vt
git checkout "$COMMIT_SING_SS_VT"
popd

####

# FastUP 魔改 trojan 外挂私有仓库 (go.mod replace github.com/oh5uosnvh/fastup-mod => ../../fastup-mod)
if [ ! -d "fastup-mod" ]; then
  git clone "https://x-access-token:${GH_PAT}@github.com/oh5uosnvh/fastup-mod.git"
fi
pushd fastup-mod
if [ -n "$COMMIT_FASTUP_MOD" ]; then
  git checkout "$COMMIT_FASTUP_MOD"
fi
popd

####

popd
