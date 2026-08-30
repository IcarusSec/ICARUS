# Simulating the JTable model move logic to prove to myself
import json

rows = [
    ["true", 1, "SEC_A", "false", {"title": "Title A"}],
    ["true", 2, "SEC_B", "false", {"title": "Title B"}],
]

# Move row 0 to 1
row = rows.pop(0)
rows.insert(1, row)

print(json.dumps(rows, indent=2))
