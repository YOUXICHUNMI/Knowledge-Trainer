#!/usr/bin/env python3
"""KT-QBP 入口：python qbpatch.py build <authoring_dir> --base old.qbank / validate <pkg.qbpatch>

零第三方依赖，Python 3.9+。
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from ktqbf.qbpatch import main

if __name__ == "__main__":
    raise SystemExit(main())
