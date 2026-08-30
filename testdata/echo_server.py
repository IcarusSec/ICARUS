# pip install flask ; python3 -m flask --app testdata/echo_server.py run -p 8000
from flask import Flask, request, jsonify
app = Flask(__name__)

@app.route("/echo", methods=["GET", "POST"])
def echo():
    values = list(request.args.values()) + list(request.form.values())
    # deterministic 4xx-baseline -> 2xx bypass, on the `q` param ONLY (so a
    # mutation of `token` carrying the same payload does NOT also 200 — keeps
    # §6.5's "exactly one finding" assertion clean)
    if "' OR '1'='1" in (request.values.get("q") or ""):
        return jsonify(ok=True), 200
    # deterministic any -> 500 on a lone quote (stack-trace-shaped body)
    if any("'" in v for v in values):
        return "java.sql.SQLException: SQL syntax error near \"'\"", 500
    # token gate reads request.values so a form-posted token works too
    if request.values.get("token") != "good":
        return "invalid token", 400
    return jsonify(args=dict(request.args), form=dict(request.form),
                   raw=request.get_data(as_text=True)), 200
