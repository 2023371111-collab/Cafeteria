#!/usr/bin/env bash
# Práctica 13: convierte los XML de JUnit en una tabla Markdown (resumen del job en GitHub Actions).
# Uso: bash resumen_pruebas.sh <carpeta-test-results> "<título>"
set -euo pipefail
DIR="${1:?carpeta de resultados}"
TITULO="${2:-Resultados de pruebas}"

"${PYTHON:-python3}" - "$DIR" "$TITULO" <<'PY'
import glob, os, sys
import xml.etree.ElementTree as ET

carpeta, titulo = sys.argv[1], sys.argv[2]
archivos = sorted(glob.glob(os.path.join(carpeta, "TEST-*.xml")))
print(f"## {titulo}\n")
if not archivos:
    print("_No se generaron resultados (la compilación falló antes de ejecutar las pruebas)._")
    sys.exit(0)

print("| Clase | Pruebas | Aprobadas | Fallidas | Omitidas | Tiempo (s) |")
print("|---|---:|---:|---:|---:|---:|")
tot = dict(t=0, ok=0, f=0, s=0, tiempo=0.0)
fallas = []
for ruta in archivos:
    s = ET.parse(ruta).getroot()
    t, f, e, sk = (int(s.get(k, 0)) for k in ("tests", "failures", "errors", "skipped"))
    tiempo = float(s.get("time", 0))
    ok = t - f - e - sk
    nombre = s.get("name", "").rsplit(".", 1)[-1]
    print(f"| {nombre} | {t} | {ok} | {f + e} | {sk} | {tiempo:.2f} |")
    tot["t"] += t; tot["ok"] += ok; tot["f"] += f + e; tot["s"] += sk; tot["tiempo"] += tiempo
    for caso in s.iter("testcase"):
        for falla in list(caso.iter("failure")) + list(caso.iter("error")):
            msg = (falla.get("message") or "").splitlines()[0][:200] if falla.get("message") else ""
            fallas.append(f"- `{nombre}.{caso.get('name')}`: {msg}")
print(f"| **Total** | **{tot['t']}** | **{tot['ok']}** | **{tot['f']}** | **{tot['s']}** | **{tot['tiempo']:.2f}** |")
print()
print("✅ Todas las pruebas ejecutadas pasaron." if tot["f"] == 0 else "❌ Pruebas fallidas:\n" + "\n".join(fallas))
PY
