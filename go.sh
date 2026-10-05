#!/data/data/com.termux/files/usr/bin/bash
set -e
cd ~/LiveCaptions
pkg install -y git gh
gh auth status >/dev/null 2>&1 || gh auth login -h github.com -p https -w
gh auth setup-git
git config --global user.email "me@example.com"
git config --global user.name "me"
[ -d .git ] || git init -q -b main
git add -A
git commit -qm "app" || true
gh repo create livecaptions-$RANDOM --private --source=. --push
echo "Building in the cloud (about 5 minutes, please wait)..."
sleep 20
ID=$(gh run list -L1 --json databaseId -q '.[0].databaseId')
gh run watch $ID --exit-status
gh run download $ID -n LiveCaptions-apk -D ~/storage/downloads
termux-open ~/storage/downloads/app-debug.apk
