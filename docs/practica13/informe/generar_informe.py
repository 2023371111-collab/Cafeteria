"""
Genera el informe de las Prácticas 13 y 14 (automatización de pruebas y CI) en Word, con el mismo
formato que las prácticas 10-12. Las cifras se leen de ../evidencias (resultados reales).
Uso: python generar_informe.py  -> Practica13_14_Automatizacion.docx
"""
import glob, json, os, re, sys
import xml.etree.ElementTree as ET
from datetime import datetime
from docx import Document
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt
from PIL import Image, ImageChops

sys.stdout.reconfigure(encoding="utf-8")
AQUI = os.path.dirname(os.path.abspath(__file__))
RAIZ = os.path.normpath(os.path.join(AQUI, "..", "..", ".."))
EV = os.path.join(AQUI, "..", "evidencias")
CAP = os.path.join(AQUI, "..", "capturas")
REC = os.path.join(AQUI, "recortes")
LOGO = r"C:\Users\panso\Desktop\uteq.png"
REPO = "https://github.com/2023371111-collab/Cafeteria"
os.makedirs(REC, exist_ok=True)


def J(n):
    return json.load(open(os.path.join(EV, n), encoding="utf-8"))


def suites(carpeta):
    """Lee los XML de JUnit: {clase: (pruebas, aprobadas, fallidas, omitidas, tiempo)}."""
    r = {}
    for ruta in sorted(glob.glob(os.path.join(EV, carpeta, "TEST-*.xml"))):
        s = ET.parse(ruta).getroot()
        t, f, e, sk = (int(s.get(k, 0)) for k in ("tests", "failures", "errors", "skipped"))
        r[s.get("name").rsplit(".", 1)[-1]] = (t, t - f - e - sk, f + e, sk, float(s.get("time", 0)))
    return r


FINAL, REGR = suites("local_final"), suites("run2_local")
TIEMPO_LOCAL = int(open(os.path.join(EV, "local_final_tiempo.txt")).read().split("=")[1])
RUNS = [(J("ci_run1_verde.json"), J("ci_run1_jobs.json")), (J("ci_run2_rojo.json"), J("ci_run2_jobs.json")),
        (J("ci_run3_verde.json"), J("ci_run3_jobs.json"))]


def seg(a, b):
    f = lambda x: datetime.strptime(x, "%Y-%m-%dT%H:%M:%SZ")
    return int((f(b) - f(a)).total_seconds())


def mmss(s):
    return f"{s // 60} min {s % 60:02d} s"


# ------------------------------------------------------------------ formato (igual que P10-P12)
doc = Document()
for s in doc.sections:
    s.page_width, s.page_height = Cm(21), Cm(29.7)
    s.left_margin = s.right_margin = s.top_margin = s.bottom_margin = Cm(2.5)
est = doc.styles["Normal"]
est.font.name = "Arial"; est.font.size = Pt(12)
est.element.rPr.rFonts.set(qn("w:eastAsia"), "Arial")
est.paragraph_format.space_after = Pt(6); est.paragraph_format.line_spacing = 1.08


def runs(p, texto, tam=None):
    for parte in re.split(r"(\*\*[^*]+\*\*|`[^`]+`)", texto):
        if not parte:
            continue
        if parte.startswith("**"):
            r = p.add_run(parte[2:-2]); r.bold = True
        elif parte.startswith("`"):
            r = p.add_run(parte[1:-1]); r.font.name = "Consolas"; r._element.rPr.rFonts.set(qn("w:eastAsia"), "Consolas")
        else:
            r = p.add_run(parte)
        if tam:
            r.font.size = Pt(tam)
    return p


def P(texto, tam=None, despues=6):
    p = doc.add_paragraph(); p.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY; p.paragraph_format.space_after = Pt(despues)
    return runs(p, texto, tam)


PENDIENTE_SALTO = [False]


def H(texto, tam=12):
    p = doc.add_paragraph(); p.paragraph_format.space_before = Pt(10); p.paragraph_format.space_after = Pt(4)
    if PENDIENTE_SALTO[0]:
        p.paragraph_format.page_break_before = True; PENDIENTE_SALTO[0] = False
    p.paragraph_format.keep_with_next = True
    r = p.add_run(texto); r.bold = True; r.font.size = Pt(tam)


def BUL(items, tam=None):
    for t in items:
        p = doc.add_paragraph(style="List Bullet"); p.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
        p.paragraph_format.space_after = Pt(2); runs(p, t, tam)


def sombra(c, color):
    tcPr = c._element.get_or_add_tcPr(); shd = OxmlElement("w:shd")
    shd.set(qn("w:val"), "clear"); shd.set(qn("w:color"), "auto"); shd.set(qn("w:fill"), color); tcPr.append(shd)


def color_res(i, v):
    if v.startswith(("Aprobad", "Pasa", "Verde", "Corregido", "Éxito")):
        return "E2F0D9"
    if v.startswith(("Fall", "Rojo")):
        return "F8D7DA"
    if v.startswith(("Omitid", "Observ", "Pendiente")):
        return "FFF2CC"
    return None


def T(enc, filas, anchos=None, tam=8.5, colores=color_res):
    t = doc.add_table(rows=1, cols=len(enc)); t.style = "Table Grid"; t.alignment = WD_TABLE_ALIGNMENT.CENTER
    t.autofit = False
    for i, h in enumerate(enc):
        c = t.rows[0].cells[i]; c.text = ""; r = c.paragraphs[0].add_run(h); r.bold = True; r.font.size = Pt(tam); sombra(c, "E7E3F1")
    for fila in filas:
        cel = t.add_row().cells
        for i, v in enumerate(fila):
            cel[i].text = ""; p = cel[i].paragraphs[0]; p.paragraph_format.space_after = Pt(0); runs(p, str(v), tam)
            if colores and colores(i, str(v)):
                sombra(cel[i], colores(i, str(v)))
    if anchos:
        for fila in t.rows:
            for i, w in enumerate(anchos):
                fila.cells[i].width = Cm(w)
    trPr = t.rows[0]._tr.get_or_add_trPr(); th = OxmlElement("w:tblHeader"); th.set(qn("w:val"), "true"); trPr.append(th)
    doc.add_paragraph().paragraph_format.space_after = Pt(2)


def recortar(nombre, alto=None):
    """Quita el espacio vacío de abajo de la captura (y la corta a 'alto' px si se indica)."""
    img = Image.open(os.path.join(CAP, nombre)).convert("RGB")
    fondo = Image.new("RGB", img.size, img.getpixel((img.width - 1, img.height - 1)))
    caja = ImageChops.difference(img, fondo).getbbox()
    abajo = min(img.height, (caja[3] + 20) if caja else img.height)
    if alto:
        abajo = min(abajo, alto)
    destino = os.path.join(REC, nombre)
    img.crop((0, 0, img.width, abajo)).save(destino)
    return destino


nf = [0]


def IMG(nombre, ancho, pie, alto=None):
    ruta = recortar(nombre, alto)
    p = doc.add_paragraph(); p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.keep_with_next = True; p.paragraph_format.space_after = Pt(0)
    p.add_run().add_picture(ruta, width=Cm(ancho))
    nf[0] += 1
    c = doc.add_paragraph(); c.alignment = WD_ALIGN_PARAGRAPH.CENTER; runs(c, f"Figura {nf[0]}. {pie}", 10)


def CODE(texto, tam=7.5):
    t = doc.add_table(rows=1, cols=1); t.style = "Table Grid"; c = t.rows[0].cells[0]; sombra(c, "F4F4F4"); c.text = ""
    p = c.paragraphs[0]; p.paragraph_format.space_after = Pt(0); p.paragraph_format.line_spacing = 1.0
    lineas = texto.strip("\n").split("\n")
    for i, l in enumerate(lineas):
        r = p.add_run(l); r.font.name = "Consolas"; r.font.size = Pt(tam); r._element.rPr.rFonts.set(qn("w:eastAsia"), "Consolas")
        if i < len(lineas) - 1:
            r.add_break()
    doc.add_paragraph().paragraph_format.space_after = Pt(2)


def SALTO():
    PENDIENTE_SALTO[0] = True


def fuente(ruta_rel):
    return open(os.path.join(RAIZ, ruta_rel), encoding="utf-8").read()


def metodo(ruta_rel, nombre):
    """Extrae una función de prueba completa (desde su @Test hasta la llave que la cierra)."""
    lineas = fuente(ruta_rel).split("\n")
    i = next(k for k, l in enumerate(lineas) if f"fun {nombre}(" in l)
    while i > 0 and lineas[i - 1].strip().startswith("@Test"):
        i -= 1
    sangria = len(lineas[i]) - len(lineas[i].lstrip())
    for j in range(i + 1, len(lineas)):
        if lineas[j].startswith(" " * sangria + "}") and len(lineas[j]) - len(lineas[j].lstrip()) == sangria:
            return "\n".join(l[sangria:] for l in lineas[i:j + 1])
    raise ValueError(nombre)


# ------------------------------------------------------------------ portada
for _ in range(2):
    doc.add_paragraph()
p = doc.add_paragraph(); p.alignment = WD_ALIGN_PARAGRAPH.CENTER; p.add_run().add_picture(LOGO, width=Cm(9.5))
doc.add_paragraph()
for texto, neg in [("Universidad Tecnológica de Querétaro", True), ("", False), ("Prácticas 13 y 14", True),
                   ("Automatización de pruebas y validación continua de la aplicación Cafetería Móvil (Kotlin)", False),
                   ("", False), ("Materia: Desarrollo Movil Integral", False), ("Profesor(a): MANUEL CONTRERAS CASTILLO", False),
                   ("Alumno: Angel Gabriel Paredes Aviles", False), ("Grupo: IDGS15", False), ("", False),
                   ("Querétaro, Qro., 24 de noviembre de 2026", False)]:
    p = doc.add_paragraph(); p.alignment = WD_ALIGN_PARAGRAPH.CENTER; p.paragraph_format.space_after = Pt(4)
    r = p.add_run(texto); r.bold = neg
doc.add_paragraph().add_run().add_break(WD_BREAK.PAGE)

# ------------------------------------------------------------------ objetivo y entorno
H("Objetivo")
P("Automatizar las pruebas de la aplicación Cafetería Móvil (Android, Kotlin + Jetpack Compose, API PHP/MySQL) y "
  "configurar un flujo de integración continua en GitHub Actions que las ejecute solo en cada push y pull request, "
  "para detectar de inmediato cualquier modificación que rompa una función crítica, la comunicación con el servidor "
  "o la persistencia de datos.")
H("Entorno")
BUL([
    f"**Repositorio:** `{REPO}` (público). Rama `main`.",
    "**Proyecto:** Kotlin 2.2, Android Gradle Plugin 9.3, Gradle 9.5, compileSdk 37. Como el proyecto es Kotlin, los "
    "pasos equivalentes a `flutter test` son `./gradlew :app:testDebugUnitTest` y `./gradlew :app:lintDebug`.",
    "**Herramientas de prueba:** JUnit 4.13, kotlinx-coroutines-test, MockWebServer 4.12 (servidor HTTP de pruebas), "
    "Robolectric 4.16 (SQLite y SharedPreferences de Android en la JVM, sin emulador) y Docker (PHP 8.3 + MySQL 8.0).",
    "**CI:** GitHub Actions, `ubuntu-latest`, JDK 21 Temurin. **Local:** Windows 11, JBR 21 de Android Studio, Docker Desktop.",
])

# ------------------------------------------------------------------ fase 1
H("Fase 1. Componentes críticos seleccionados")
P("A partir de los resultados de las prácticas 11 (White Box) y 12 (optimización y resiliencia) se eligieron tres "
  "componentes y los defectos o riesgos ya detectados que una prueba automatizada puede impedir que regresen:")
T(["Componente", "Tipo", "Código", "Defecto o riesgo detectado antes que se previene"], [
    ["Inicio de sesión, registro y captura de pedido", "Función crítica del móvil",
     "`ValidadorAcceso.kt`, `ValidadorPedido.kt`",
     "Entradas vacías, enormes o con caracteres de inyección enviadas a login.php; mensajes de error que revelan datos (P5/P11). Teléfonos o coordenadas inválidos."],
    ["Servicios login.php, registro.php y pedidos.php", "Servicio / endpoint",
     "`AuthRepository.kt`, `OrderRepository.kt`, `Reintentos.kt`",
     "DEF-07 (JSON incompleto cerraba la app), pedidos duplicados al reintentar (P11, corregido con Idempotency-Key en P12), "
     "reintentar errores 4xx, servidor que no responde."],
    ["Room (usuarios), carrito (SharedPreferences) y MySQL (pedidos)", "Persistencia",
     "`AppDatabase.kt`, `UsuarioDao.kt`, `CartViewModel.kt`",
     "Carrito guardado corrupto que cerraba la app al iniciar; pérdida de usuarios en la migración 1→2 de Room; pedido no almacenado."],
], anchos=[3.6, 2.4, 3.6, 6.4])

# ------------------------------------------------------------------ fase 2
H("Fase 2. Pruebas unitarias (AUT-01 a AUT-03)")
P("Se crearon 24 pruebas unitarias en tres clases. Cada clase cubre los cinco tipos de caso solicitados. Como Kotlin "
  "no permite pasar `null` a un parámetro `String` (el compilador lo rechaza), el caso \"nulo\" se cubre con cadenas "
  "vacías o de solo espacios, con `NaN` en coordenadas y, en integración, con campos `null` en el JSON del servidor.")
T(["ID", "Clase de prueba", "Pruebas", "Casos cubiertos"], [
    ["AUT-01", "`AUT01LoginValidacionTest`", "9",
     "**Válida:** usuario y contraseña correctos, registro completo. **Vacía:** \"\", \"    \" y contraseña vacía. "
     "**Fuera de rango:** usuario de 30/31 caracteres, contraseña de 7, 64 y 65. **Esperado:** mensajes genéricos exactos "
     "y lista de campos con error. **Maliciosa:** `admin' OR '1'='1`, `<script>`; el mensaje nunca repite la entrada."],
    ["AUT-02", "`AUT02PedidoValidacionTest`", "7",
     "**Válida:** nombre, teléfono, folio, notas y ubicación de la UTEQ. **Vacía:** campos en blanco. **Fuera de rango:** "
     "teléfono de 9/11 dígitos o con letras, notas de 151 caracteres, latitud 90.0001, longitud -180.5 y `NaN`. "
     "**Esperado:** `cmd7k3f9q` → `CMD-7K3F9Q`."],
    ["AUT-03", "`AUT03ReintentosExcepcionesTest`", "8",
     "**Válida:** error 503 que se reintenta y termina en éxito. **Fuera de rango:** 0 intentos, intento 1000, códigos "
     "400/499/600 (no reintentables) y 408/429/5xx (reintentables). **Esperado:** esperas 500 → 1000 → 2000 → 4000 ms con tope. **Excepciones:** `IllegalArgumentException`, "
     "un 4xx se propaga sin reintentar, al agotar intentos se lanza el último error."],
], anchos=[1.6, 4.4, 1.6, 8.4], tam=8)

# ------------------------------------------------------------------ fase 3
H("Fase 3. Pruebas de integración y persistencia (AUT-04 a AUT-06)")
BUL([
    "**AUT-04 (7 pruebas):** `AuthRepository` (cliente HTTP real) ↔ servidor MockWebServer que escucha en un puerto real. "
    "Verifica que el login viaja por POST con JSON (la contraseña nunca en la URL) y que la app interpreta bien 200, 401, "
    "409 y 500, una respuesta HTML, el servidor apagado y el servidor que acepta la conexión y no contesta (la app espera "
    "su límite de 8 s y muestra \"No fue posible conectar\" en lugar de colgarse). Para hacerlo posible se cambió "
    "`AuthRepository` para recibir la URL base como parámetro.",
    "**AUT-05 (5 pruebas):** app ↔ API PHP real ↔ MySQL 8 en Docker (`backend/pruebas`), sin dobles de prueba. Comprueba "
    "los códigos HTTP del contrato (200, 4xx con datos inválidos, 401), un login real, que el pedido enviado se almacena "
    "con los precios del servidor y se recupera por folio y teléfono, que reenviarlo con la misma clave no lo duplica y "
    "que un producto no disponible se rechaza sin exponer SQL.",
    "**AUT-06 (6 pruebas):** persistencia con Robolectric: insertar, consultar, modificar y eliminar en Room; migración "
    "de una base versión 1 real a la versión 2 conservando los registros; carrito que se conserva al reabrir la app y "
    "carrito corrupto que se descarta sin cerrarla.",
])
P("Adaptación justificada: para que la integración se pruebe en cada push sin depender del hosting de producción "
  "(ni modificar sus datos), el job de integración del CI levanta el mismo código PHP con una base MySQL en memoria que "
  "solo contiene datos ficticios.")

# ------------------------------------------------------------------ fase 4
H("Fase 4. Pipeline de integración continua")
P("El archivo `.github/workflows/ci.yml` (Anexo C) se ejecuta en cada `push`, en cada `pull_request` y manualmente. "
  "Tiene dos jobs en paralelo:")
BUL([
    "**AUT-07, \"Pruebas unitarias + Lint\":** instala JDK 21, prepara Gradle con caché, ejecuta "
    "`./gradlew :app:testDebugUnitTest` (todas las pruebas; AUT-05 queda omitida en este job) y `./gradlew :app:lintDebug`.",
    "**AUT-08, \"Integración con API PHP + MySQL\":** copia la plantilla `backend/db.example.php`, levanta "
    "`docker compose` con PHP 8.3 y MySQL 8.0, espera a que la API responda y ejecuta AUT-05 y la prueba de integración de "
    "la práctica 4 con `-PapiPruebasUrl=http://localhost:8080/api/`.",
    "Ambos publican una tabla de resultados en el resumen de la ejecución (`.github/scripts/resumen_pruebas.sh`) y "
    "guardan los reportes JUnit/HTML/Lint como artefactos. Un push nuevo cancela la ejecución anterior de la misma rama.",
    "**Seguridad:** `db.php` (contraseña de MySQL de producción), la llave de servicio de Firebase y `google-services.json` "
    "se excluyeron con `.gitignore` antes del primer commit; se revisó que ninguno de los 154 archivos subidos los contuviera.",
])

# ------------------------------------------------------------------ fase 5
H("Fase 5. Validación y análisis de resultados")
H("Ejecución local completa (con backend Docker)", 11)
aut = {k: v for k, v in FINAL.items() if k.startswith("AUT")}
otras = {k: v for k, v in FINAL.items() if not k.startswith("AUT")}
filas = [[k, v[0], v[1], v[2], v[3], f"{v[4]:.2f}"] for k, v in aut.items()]
o = [sum(v[i] for v in otras.values()) for i in range(5)]
filas.append([f"Pruebas de prácticas anteriores ({len(otras)} clases)", o[0], o[1], o[2], o[3], f"{o[4]:.2f}"])
tot = [sum(v[i] for v in FINAL.values()) for i in range(5)]
filas.append(["**Total**", f"**{tot[0]}**", f"**{tot[1]}**", f"**{tot[2]}**", f"**{tot[3]}**", f"**{tot[4]:.2f}**"])
T(["Clase", "Ejecutadas", "Aprobadas", "Fallidas", "Omitidas", "Tiempo (s)"], filas, anchos=[6.4, 1.9, 1.9, 1.7, 1.7, 1.9])
n_aut = sum(v[0] for v in aut.values())
P(f"Las {n_aut} pruebas nuevas y las {o[0]} de prácticas anteriores pasaron (100 %). Tiempo de las pruebas: "
  f"{tot[4]:.1f} s; tiempo total de `testDebugUnitTest` + `lintDebug`: {mmss(TIEMPO_LOCAL)}. Lint terminó sin errores.")

H("Ejecuciones automáticas en GitHub Actions", 11)
filas = []
for run, jobs in RUNS:
    j = {x["name"].split(" (")[0]: x for x in jobs["jobs"]}
    u = j["Pruebas unitarias + Lint"]; i = j["Integración con API PHP + MySQL"]
    res = lambda x: "Aprobado" if x["conclusion"] == "success" else "Fallido"
    filas.append([f"#{run['run_number']}", f"`{run['head_sha'][:7]}`", run["display_title"][:58] + ("…" if len(run["display_title"]) > 58 else ""),
                  f"{res(u)} ({mmss(seg(u['started_at'], u['completed_at']))})",
                  f"{res(i)} ({mmss(seg(i['started_at'], i['completed_at']))})",
                  "Verde" if run["conclusion"] == "success" else "Rojo", mmss(seg(run["run_started_at"], run["updated_at"]))])
T(["Run", "Commit", "Cambio", "AUT-07 unitarias + Lint", "AUT-08 integración", "Estado", "Duración"], filas,
  anchos=[1.1, 1.6, 4.6, 2.7, 2.7, 1.4, 2.0], tam=7.5)
r = REGR
P(f"Para comprobar que el pipeline protege la calidad se subió una **regresión intencional** (run #2): la validación del "
  f"teléfono se cambió a `^\\d{{8,10}}$`, cuando pedidos.php exige exactamente 10 dígitos. El job AUT-07 falló: de "
  f"{sum(v[0] for v in r.values())} pruebas, {sum(v[2] for v in r.values())} fallaron "
  f"(`AUT02PedidoValidacionTest.telefono_con_9_u_11_digitos_o_letras_se_rechaza`: esperaba \"Debe tener 10 dígitos\" y "
  f"obtuvo `null` con `442123456`, y `ValidadorPedidoTest.telefono_de_10_digitos`) y Lint ya no se ejecutó. Se "
  f"revirtió la expresión regular y en el run #3 el pipeline volvió a verde, demostrando que se ejecuta de nuevo con "
  f"cada push.")

H("Errores encontrados y cambios realizados", 11)
T(["#", "Error / hallazgo", "Cambio realizado", "Estado"], [
    ["1", "Regresión: teléfono de 8-9 dígitos aceptado (run #2 rojo, 2 pruebas fallidas).",
     "Se restauró `^\\d{10}$` en `ValidadorPedido.kt` (commit d40dd16).", "Corregido (run #3 verde)"],
    ["2", "GitHub avisó que checkout, setup-java y setup-gradle v4 usan Node.js 20 (obsoleto).",
     "Acciones actualizadas a v5 en `ci.yml`.", "Corregido"],
    ["3", "`upload-artifact@v5` aún muestra el aviso de Node.js 20.", "No afecta el resultado; actualizar cuando haya versión nueva.", "Observación"],
    ["4", "`db.php` tenía la contraseña de MySQL de producción y las copias en docs/ también; subirlas la habría expuesto.",
     "`.gitignore` para todos los `db.php`, llaves y `google-services.json`; plantilla `db.example.php` para el CI.", "Corregido"],
    ["5", "`AuthRepository` tenía la URL fija y no se podía probar sin producción.", "Se agregó el parámetro `baseUrl` (por omisión la de producción).", "Corregido"],
    ["6", "Robolectric 4.16 no emula Android 37 (compileSdk).", "`robolectric.properties` con `sdk=35`.", "Corregido"],
    ["7", "Primer push rechazado (HTTP 403): la PC usaba otra cuenta de GitHub.", "Push con la cuenta dueña del repositorio.", "Corregido"],
    ["8", "Gradle local fallaba con el `jlink` de otro IDE.", "`gradlew --stop` y JDK de Android Studio (en CI no ocurre).", "Corregido"],
], anchos=[0.7, 5.6, 6.6, 3.1], tam=8)

# ------------------------------------------------------------------ matriz
H("Matriz de automatización")
T(["ID", "Tipo", "Prueba / componente", "Casos", "Evidencia", "Estado"], [
    ["AUT-01", "Unitarias", "Validación de inicio de sesión y registro (`AUT01LoginValidacionTest`)", "9", "Run #1 y #3, local", "Aprobada"],
    ["AUT-02", "Unitarias", "Validación de captura de pedido (`AUT02PedidoValidacionTest`)", "7", "Run #2 la detectó; #3", "Aprobada"],
    ["AUT-03", "Unitarias", "Reintentos y manejo de excepciones (`AUT03ReintentosExcepcionesTest`)", "8", "Run #1 y #3, local", "Aprobada"],
    ["AUT-04", "Integración", "App ↔ login.php / registro.php con servidor HTTP de pruebas (`AUT04LoginIntegracionTest`)", "7", "Run #1 y #3, local", "Aprobada"],
    ["AUT-05", "Integración", "App ↔ API PHP ↔ MySQL 8 real en Docker (`AUT05PedidosApiRealTest`)", "5", "Job AUT-08, local", "Aprobada"],
    ["AUT-06", "Persistencia", "Room CRUD + migración 1→2, carrito SharedPreferences (`AUT06PersistenciaTest`)", "6", "Run #1 y #3, local", "Aprobada"],
    ["AUT-07", "CI", "Job \"Pruebas unitarias + Lint\" en cada push / pull request", "—", "Runs #1, #2 (rojo esperado), #3", "Aprobada"],
    ["AUT-08", "CI", "Job \"Integración con API PHP + MySQL\" y re-ejecución tras nuevo push", "—", "Runs #1, #2, #3", "Aprobada"],
], anchos=[1.4, 2.0, 7.2, 1.2, 3.0, 1.7], tam=8)

# ------------------------------------------------------------------ conclusiones
H("Conclusiones")
P("La automatización convierte el conocimiento de las prácticas anteriores en una red de seguridad permanente. Los "
  "defectos encontrados en la auditoría White Box y en las pruebas de resiliencia (JSON incompleto, pedidos duplicados, "
  "carrito corrupto, errores 4xx reintentados) ya estaban corregidos, pero nada impedía que una modificación futura los "
  "volviera a introducir. Ahora cada uno está descrito por una prueba que se ejecuta sola en cada push, en menos de cinco "
  "minutos y sin que el desarrollador tenga que acordarse de hacerlo.")
P("La regresión intencional lo demostró: un cambio de una línea que parecía inofensivo (aceptar teléfonos más cortos) "
  "habría generado pedidos rechazados por el servidor en producción; el pipeline lo marcó en rojo en 2 min 23 s, señaló "
  "la prueba y el valor exacto que fallaba, y después confirmó la corrección. Combinar niveles fue importante: las "
  "pruebas unitarias son rápidas y precisas, las de integración con servidor simulado reproducen fallas de red que en "
  "producción no se pueden provocar, y las del backend real en Docker verifican el contrato verdadero entre la app, PHP "
  "y MySQL sin tocar datos reales. Robolectric permitió probar Room y SharedPreferences sin emulador, lo que hace viable "
  "ejecutarlas en cada push.")
P("Finalmente, preparar el repositorio para CI obligó a separar las credenciales del código (plantilla `db.example.php` y "
  "variables de entorno), un beneficio de seguridad adicional. Como mejora pendiente queda agregar pruebas de interfaz "
  "instrumentadas en un emulador del CI y proteger la rama `main` para exigir el pipeline en verde antes de integrar un "
  "pull request.")

# ------------------------------------------------------------------ anexos
SALTO()
H("Anexo A. Evidencia de ejecución")
IMG("01_actions_lista.png", 15, "Pestaña Actions: las tres ejecuciones automáticas (verde, rojo intencional, verde).", alto=520)
IMG("02_run1_verde.png", 15, "Run #1: ambos jobs aprobados al subir las pruebas y el pipeline.", alto=700)
IMG("03_run2_rojo.png", 15, "Run #2: el job AUT-07 falla por la regresión intencional del teléfono.", alto=1140)
IMG("07_gradle_local_rojo.png", 12, "Reproducción local del run #2: 2 pruebas fallidas (AUT-02 y ValidadorPedidoTest).", alto=540)
IMG("04_run3_verde.png", 15, "Run #3: después de la corrección el pipeline vuelve a verde; solo quedan avisos de Node.js 20.", alto=950)
IMG("08_gradle_local_verde.png", 12, "Reporte de Gradle de la ejecución local completa con backend Docker: 123 pruebas, 100 %.")

SALTO()
H("Anexo B. Código de las pruebas (extractos)")
P(f"El código completo está en `app/src/test/java/com/example/cafeteria/AUT0*.kt` del repositorio ({REPO}).", 10)
for archivo, metodos in [
    ("AUT01LoginValidacionTest.kt", ["campos_vacios_o_solo_espacios_se_rechazan_con_mensaje_generico", "usuario_en_el_limite_se_acepta_y_uno_mas_se_rechaza"]),
    ("AUT02PedidoValidacionTest.kt", ["telefono_con_9_u_11_digitos_o_letras_se_rechaza", "coordenadas_fuera_del_planeta_o_nan_se_rechazan"]),
    ("AUT03ReintentosExcepcionesTest.kt", ["cero_intentos_lanza_IllegalArgumentException", "error_4xx_no_se_reintenta_y_se_propaga_la_misma_excepcion"]),
    ("AUT04LoginIntegracionTest.kt", ["la_app_envia_credenciales_por_POST_y_recibe_una_sesion_valida", "servidor_que_no_responde_muestra_mensaje_de_conexion"]),
    ("AUT05PedidosApiRealTest.kt", ["la_api_devuelve_los_codigos_http_esperados", "el_pedido_enviado_se_almacena_y_se_puede_consultar"]),
    ("AUT06PersistenciaTest.kt", ["migracion_1_a_2_conserva_los_usuarios_existentes", "carrito_guardado_corrupto_se_descarta_sin_cerrar_la_app"]),
]:
    ruta = "app/src/test/java/com/example/cafeteria/" + archivo
    H(archivo, 10)
    CODE("\n\n".join(metodo(ruta, m) for m in metodos), 7)

SALTO()
H("Anexo C. Configuración del pipeline (.github/workflows/ci.yml)")
CODE(fuente(".github/workflows/ci.yml"), 6.5)

salida = os.path.join(AQUI, "Practica13_14_Automatizacion.docx")
doc.save(salida)
print("Guardado:", salida, "figuras:", nf[0])
