#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
用 GitHub Git Data API 提交（github.com:443 间歇被阻时用，api.github.com 稳定）。
用法: python3 tools/api_commit.py "commit message" <文件路径> [...]
"""
import base64, json, os, subprocess, sys, time, urllib.request

API = "https://api.github.com"
REPO = os.environ.get("GH_REPO", "boaom/huami-extractor")
TOKEN = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")


def req(method, path, body=None, raw=False):
    url = path if path.startswith("http") else API + path
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, data=data, method=method)
    r.add_header("Authorization", "Bearer " + TOKEN)
    r.add_header("Accept", "application/vnd.github+json")
    r.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(r, timeout=60) as resp:
        content = resp.read()
        return content if raw else json.loads(content or b"{}")


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    msg = sys.argv[1]
    files = sys.argv[2:]

    ref = req("GET", f"/repos/{REPO}/git/ref/heads/main")
    sha = ref["object"]["sha"]
    commit = req("GET", f"/repos/{REPO}/git/commits/{sha}")
    base_tree = commit["tree"]["sha"]

    tree_entries = []
    for path in files:
        with open(path, "rb") as f:
            blob = req("POST", f"/repos/{REPO}/git/blobs",
                       {"content": base64.b64encode(f.read()).decode(), "encoding": "base64"})
        tree_entries.append({"path": path, "mode": "100644", "type": "blob", "sha": blob["sha"]})

    tree = req("POST", f"/repos/{REPO}/git/trees",
               {"base_tree": base_tree, "tree": tree_entries})
    new_commit = req("POST", f"/repos/{REPO}/git/commits",
                     {"message": msg, "tree": tree["sha"], "parents": [sha]})
    req("PATCH", f"/repos/{REPO}/git/refs/heads/main",
        {"sha": new_commit["sha"], "force": False})
    print("committed", new_commit["sha"], "files:", len(tree_entries))


if __name__ == "__main__":
    main()
