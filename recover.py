import json

log_file = "/home/trecto/.gemini/antigravity-cli/brain/69686090-75d1-42b9-ad88-2763364d720c/.system_generated/logs/transcript.jsonl"
with open(log_file, "r") as f:
    for line in f:
        data = json.loads(line)
        if "content" in data:
            if "The following changes were made" in data["content"] and "ReportingSettingsTab.java" in data["content"]:
                print("FOUND A DIFF!")
                # Let's write the diff content to a file so we can inspect it
                with open("/tmp/diffs_found.txt", "a") as df:
                    df.write(data["content"] + "\n\n")
        
        # Also check tool calls
        if "tool_calls" in data:
            for tc in data["tool_calls"]:
                if tc["name"] == "replace_file_content" and "ReportingSettingsTab.java" in tc["args"].get("TargetFile", ""):
                    print("FOUND TOOL CALL:", tc["args"]["Instruction"])
                    with open("/tmp/diffs_found.txt", "a") as df:
                        df.write(json.dumps(tc["args"]) + "\n\n")

print("Done parsing.")
