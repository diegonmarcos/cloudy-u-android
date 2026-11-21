#!/usr/bin/env python3
"""Prints the cached mail rows of a copied sterna.db (the #1837 offline proof's evidence). Stdlib only.
   mail-offline-db.py DIR   DIR holds sterna.db (+ -wal / -shm). Exit 0 always; the lines are the evidence."""
import os
import sqlite3
import sys

d = sys.argv[1]
c = sqlite3.connect("file:%s?mode=ro" % os.path.join(d, "sterna.db"), uri=True)
print("user_version", c.execute("PRAGMA user_version").fetchone()[0])
print("emails:")
for r in c.execute("SELECT id, mailboxId, subject, seen FROM emails ORDER BY id"):
    print("  ", r)
print("email_mailboxes:")
try:
    for r in c.execute("SELECT emailId, mailboxId FROM email_mailboxes ORDER BY emailId, mailboxId"):
        print("  ", r)
except sqlite3.Error as e:
    print("   error:", e)
