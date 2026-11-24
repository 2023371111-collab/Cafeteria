// =============================================
// Pruebas automáticas de las reglas de seguridad de Firestore (práctica 10).
// Corren contra el EMULADOR oficial de Firestore con el archivo firestore.rules real:
//   cd firebase && npm test
// Cada prueba usa un cliente autenticado como cierto usuario (o sin sesión) y comprueba
// que Firestore PERMITA o RECHACE la operación, sin pasar por la app.
// =============================================
import { test, before, beforeEach, after } from 'node:test';
import { readFileSync } from 'node:fs';
import {
  initializeTestEnvironment, assertSucceeds, assertFails,
} from '@firebase/rules-unit-testing';
import {
  doc, getDoc, setDoc, updateDoc, deleteDoc, collection, query, where, getDocs,
  serverTimestamp, increment, writeBatch, Timestamp, addDoc,
} from 'firebase/firestore';

let env;

// Cuentas de prueba (UID → rol)
const ADMIN = 'uid-admin';
const AGENTE = 'uid-agente';
const ANA = 'uid-ana';     // usuario A
const BETO = 'uid-beto';   // usuario B

function incidencia(id, creadoPor, extra = {}) {
  return {
    id,
    titulo: `Reporte ${id}`,
    tipo: 'Falla en pedido',
    equipo: 'Terminal de caja 1',
    descripcion: 'La terminal no imprime el ticket.',
    estado: 'Pendiente',
    prioridad: 'Media',
    dirigidoA: 'soporte',
    creadoPor,
    creadoPorNombre: creadoPor,
    asignadoA: null,
    asignadoNombre: null,
    activo: true,
    version: 1,
    creadoEn: serverTimestamp(),
    actualizadoEn: serverTimestamp(),
    actualizadoPor: creadoPor,
    actualizadoPorNombre: creadoPor,
    ...extra,
  };
}

/** Cambio válido: sube la versión en 1 y deja la huella de quién lo hizo. */
function cambio(uid, campos) {
  return { ...campos, version: increment(1), actualizadoEn: serverTimestamp(), actualizadoPor: uid, actualizadoPorNombre: uid };
}

const db = (uid) => (uid ? env.authenticatedContext(uid, { email: `${uid}@prueba.test` }) : env.unauthenticatedContext()).firestore();

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-cafeteria',
    firestore: { rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8'), host: '127.0.0.1', port: 8085 },
  });
});

beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (ctx) => {
    const f = ctx.firestore();
    const perfil = (nombre, rol) => ({ nombre, correo: `${nombre}@prueba.test`, rol, dispositivo: 'Emulador', conectado: true, ultimaConexion: Timestamp.now() });
    await setDoc(doc(f, 'usuarios', ADMIN), perfil('Admin', 'admin'));
    await setDoc(doc(f, 'usuarios', AGENTE), perfil('Agente', 'agente'));
    await setDoc(doc(f, 'usuarios', ANA), perfil('Ana', 'usuario'));
    await setDoc(doc(f, 'usuarios', BETO), perfil('Beto', 'usuario'));
    const sello = { creadoEn: Timestamp.now(), actualizadoEn: Timestamp.now() };
    await setDoc(doc(f, 'incidencias', 'REP_ANA'), incidencia('REP_ANA', ANA, sello));
    await setDoc(doc(f, 'incidencias', 'REP_BETO'), incidencia('REP_BETO', BETO, sello));
    await setDoc(doc(f, 'incidencias', 'REP_ADMIN'), incidencia('REP_ADMIN', BETO, { ...sello, dirigidoA: 'admin' }));
  });
});

after(async () => { await env?.cleanup(); });

// ---------- Matriz SEG ----------

test('SEG-03 A crea una incidencia con su propio UID → permitido', async () => {
  await assertSucceeds(setDoc(doc(db(ANA), 'incidencias', 'REP001'), incidencia('REP001', ANA)));
});

test('SEG-04 A crea una incidencia con el UID de B → rechazado', async () => {
  await assertFails(setDoc(doc(db(ANA), 'incidencias', 'REP002'), incidencia('REP002', BETO)));
});

test('SEG-05 A lee directamente la incidencia de B → rechazado', async () => {
  await assertFails(getDoc(doc(db(ANA), 'incidencias', 'REP_BETO')));
});

test('SEG-05b A consulta un ID que no existe → permitido (resultado vacío, TC06)', async () => {
  const snap = await assertSucceeds(getDoc(doc(db(ANA), 'incidencias', 'REP999')));
  if (snap.exists()) throw new Error('No debería existir');
});

test('SEG-06 A modifica la incidencia de B → rechazado', async () => {
  await assertFails(updateDoc(doc(db(ANA), 'incidencias', 'REP_BETO'), cambio(ANA, { prioridad: 'Crítica' })));
});

test('SEG-07 A elimina la incidencia de B (borrado físico y baja lógica) → rechazado', async () => {
  await assertFails(deleteDoc(doc(db(ANA), 'incidencias', 'REP_BETO')));
  await assertFails(updateDoc(doc(db(ANA), 'incidencias', 'REP_BETO'), cambio(ANA, { activo: false })));
});

test('SEG-08 A cambia el estado de su incidencia → permitido', async () => {
  await assertSucceeds(updateDoc(doc(db(ANA), 'incidencias', 'REP_ANA'), cambio(ANA, { estado: 'En proceso' })));
});

test('SEG-09 A cambia el propietario de su incidencia → rechazado', async () => {
  await assertFails(updateDoc(doc(db(ANA), 'incidencias', 'REP_ANA'), cambio(ANA, { creadoPor: BETO })));
});

test('SEG-10 sin sesión no se lee ni se escribe → rechazado', async () => {
  await assertFails(getDoc(doc(db(null), 'incidencias', 'REP_ANA')));
  await assertFails(getDocs(collection(db(null), 'incidencias')));
  await assertFails(setDoc(doc(db(null), 'incidencias', 'REP003'), incidencia('REP003', 'anonimo')));
});

// ---------- Consultas (las reglas no filtran) ----------

test('Usuario: consulta filtrada por su UID → permitido; consulta de todo → rechazado', async () => {
  await assertSucceeds(getDocs(query(collection(db(ANA), 'incidencias'), where('activo', '==', true), where('creadoPor', '==', ANA))));
  await assertFails(getDocs(query(collection(db(ANA), 'incidencias'), where('activo', '==', true))));
});

test('Agente: ve la mesa de ayuda pero no las solicitudes directas al admin', async () => {
  await assertSucceeds(getDocs(query(collection(db(AGENTE), 'incidencias'), where('activo', '==', true), where('dirigidoA', '==', 'soporte'))));
  await assertSucceeds(getDoc(doc(db(AGENTE), 'incidencias', 'REP_BETO')));
  await assertFails(getDoc(doc(db(AGENTE), 'incidencias', 'REP_ADMIN')));
});

test('Admin: consulta todas las incidencias (pregunta 6)', async () => {
  await assertSucceeds(getDocs(query(collection(db(ADMIN), 'incidencias'), where('activo', '==', true))));
  await assertSucceeds(getDoc(doc(db(ADMIN), 'incidencias', 'REP_ADMIN')));
});

test('Agente atiende (modifica) la incidencia de otro; el usuario no puede asignarse un agente', async () => {
  await assertSucceeds(updateDoc(doc(db(AGENTE), 'incidencias', 'REP_BETO'), cambio(AGENTE, { estado: 'En proceso', asignadoA: AGENTE, asignadoNombre: 'Agente' })));
  await assertFails(updateDoc(doc(db(ANA), 'incidencias', 'REP_ANA'), cambio(ANA, { asignadoA: ANA, asignadoNombre: 'Ana' })));
});

test('Borrado físico solo el admin', async () => {
  await assertFails(deleteDoc(doc(db(ANA), 'incidencias', 'REP_ANA')));
  await assertSucceeds(deleteDoc(doc(db(ADMIN), 'incidencias', 'REP_ANA')));
});

// ---------- Validación de datos (pregunta 5) ----------

test('Campos inesperados o tipos incorrectos → rechazado', async () => {
  await assertFails(setDoc(doc(db(ANA), 'incidencias', 'REP004'), { ...incidencia('REP004', ANA), esAdmin: true }));
  await assertFails(setDoc(doc(db(ANA), 'incidencias', 'REP005'), incidencia('REP005', ANA, { prioridad: 5 })));
  await assertFails(setDoc(doc(db(ANA), 'incidencias', 'REP006'), incidencia('REP006', ANA, { prioridad: 'Urgentísima' })));
  await assertFails(setDoc(doc(db(ANA), 'incidencias', 'REP007'), incidencia('REP007', ANA, { titulo: '' })));
  await assertFails(setDoc(doc(db(ANA), 'incidencias', 'rep008'), incidencia('rep008', ANA)));
});

test('TC03: guardar otra vez un ID existente no lo sobrescribe (sería un update sin subir la versión)', async () => {
  await assertFails(setDoc(doc(db(ANA), 'incidencias', 'REP_ANA'), incidencia('REP_ANA', ANA)));
});

test('Control de concurrencia: un cambio que no sube la versión en 1 → rechazado', async () => {
  await assertFails(updateDoc(doc(db(ANA), 'incidencias', 'REP_ANA'), { estado: 'Resuelta', actualizadoPor: ANA, actualizadoEn: serverTimestamp() }));
  await assertFails(updateDoc(doc(db(ANA), 'incidencias', 'REP_ANA'),
    { estado: 'Resuelta', version: 7, actualizadoPor: ANA, actualizadoEn: serverTimestamp() }));
  await assertSucceeds(updateDoc(doc(db(ANA), 'incidencias', 'REP_ANA'),
    { estado: 'Resuelta', version: 2, actualizadoPor: ANA, actualizadoEn: serverTimestamp() }));
});

// ---------- Roles ----------

test('Un usuario no puede subirse el rol ni crear su perfil como admin', async () => {
  await assertFails(updateDoc(doc(db(ANA), 'usuarios', ANA), { rol: 'admin' }));
  await assertFails(setDoc(doc(db('uid-nuevo'), 'usuarios', 'uid-nuevo'),
    { nombre: 'Nuevo', correo: 'uid-nuevo@prueba.test', rol: 'admin', dispositivo: 'X', conectado: true, ultimaConexion: serverTimestamp() }));
  await assertSucceeds(setDoc(doc(db('uid-nuevo'), 'usuarios', 'uid-nuevo'),
    { nombre: 'Nuevo', correo: 'uid-nuevo@prueba.test', rol: 'usuario', dispositivo: 'X', conectado: true, ultimaConexion: serverTimestamp() }));
});

test('Usuario: puede ver UN perfil, pero no listar todas las cuentas', async () => {
  await assertSucceeds(getDoc(doc(db(ANA), 'usuarios', AGENTE)));
  await assertFails(getDocs(collection(db(ANA), 'usuarios')));
  await assertSucceeds(getDocs(collection(db(AGENTE), 'usuarios')));
});

test('URL preasignada: el admin crea la invitación, A la usa una vez, B ya no puede', async () => {
  const expira = Timestamp.fromMillis(Date.now() + 3_600_000);
  await assertFails(setDoc(doc(db(ANA), 'invitaciones', 'CODIGO01'), { rol: 'agente', creadaPor: ANA, creadaEn: serverTimestamp(), expira, usadaPor: null }));
  await assertSucceeds(setDoc(doc(db(ADMIN), 'invitaciones', 'CODIGO01'), { rol: 'agente', creadaPor: ADMIN, creadaEn: serverTimestamp(), expira, usadaPor: null }));

  // Pedir un rol distinto al de la invitación → rechazado
  const fa = db(ANA);
  const trampa = writeBatch(fa);
  trampa.update(doc(fa, 'invitaciones', 'CODIGO01'), { usadaPor: ANA });
  trampa.update(doc(fa, 'usuarios', ANA), { rol: 'admin', invitacion: 'CODIGO01' });
  await assertFails(trampa.commit());

  const ok = writeBatch(fa);
  ok.update(doc(fa, 'invitaciones', 'CODIGO01'), { usadaPor: ANA });
  ok.update(doc(fa, 'usuarios', ANA), { rol: 'agente', invitacion: 'CODIGO01' });
  await assertSucceeds(ok.commit());

  // La misma invitación ya no sirve para B
  const fb = db(BETO);
  const otra = writeBatch(fb);
  otra.update(doc(fb, 'invitaciones', 'CODIGO01'), { usadaPor: BETO });
  otra.update(doc(fb, 'usuarios', BETO), { rol: 'agente', invitacion: 'CODIGO01' });
  await assertFails(otra.commit());
});

// ---------- Conversación (práctica 7) ----------

test('Mensajes: solo los participantes escriben, con su propio UID y sin mensajes vacíos', async () => {
  const msg = (uid, texto, extra = {}) => ({
    emisorUid: uid, emisorNombre: uid, emisorRol: 'usuario', emisorDispositivo: 'Emulador',
    para: 'todos', texto, archivoId: null, archivoNombre: null, archivoTipo: null, archivoBytes: 0,
    enviadoEn: serverTimestamp(), ...extra,
  });
  await assertSucceeds(addDoc(collection(db(ANA), 'incidencias', 'REP_ANA', 'mensajes'), msg(ANA, 'Hola, sigue fallando')));
  await assertSucceeds(addDoc(collection(db(AGENTE), 'incidencias', 'REP_ANA', 'mensajes'), msg(AGENTE, 'Lo reviso')));
  await assertFails(addDoc(collection(db(BETO), 'incidencias', 'REP_ANA', 'mensajes'), msg(BETO, 'Me meto')));
  await assertFails(addDoc(collection(db(ANA), 'incidencias', 'REP_ANA', 'mensajes'), msg(BETO, 'Me hago pasar por Beto')));
  await assertFails(addDoc(collection(db(ANA), 'incidencias', 'REP_ANA', 'mensajes'), msg(ANA, '')));
  await assertFails(getDocs(collection(db(BETO), 'incidencias', 'REP_ANA', 'mensajes')));
});
