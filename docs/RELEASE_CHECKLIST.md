# RELEASE CHECKLIST — RutaAlmacen (Sistema de Suscripciones)

Esta guía cubre todo lo que tienes que hacer **por fuera del código** para que el sistema de compra de planes funcione 100% cuando publiques en Google Play. El código del cliente Android ya está endurecido y la Cloud Function de verificación ya está implementada.

---

## 1. Google Play Console — Crear los productos de suscripción

### 1.1 Subir un primer AAB firmado (requisito previo)
Google no activa las suscripciones hasta que tengas al menos una versión publicada (puede ser Internal Testing).

```powershell
.\gradlew.bat :app:bundleRelease
```

Sube el `.aab` resultante a **Internal testing** desde Play Console → Release → Testing → Internal testing.

### 1.2 Crear las suscripciones
Play Console → Monetize → Products → **Subscriptions** → Create subscription.

Crear **3 suscripciones** con estos **Product IDs exactos** (están hardcoded en `Plan.kt`):

| Product ID | Nombre | Precio Chile | Plan base |
|---|---|---|---|
| `vendedor_mensual` | Vendedor | $3.990 CLP / mes | Auto-renovable mensual |
| `comercio_mensual` | Comercio | $7.990 CLP / mes | Auto-renovable mensual |
| `empresarial_mensual` | Empresarial | $14.990 CLP / mes | Auto-renovable mensual |

Para **cada** suscripción:
1. Agrega un **Base plan** mensual (Auto-renewing).
2. En el base plan, agrega un **Offer** y ponle el **tag** `plan-activo` (esto es lo que busca `BillingManager.kt:84` y `:126`).
3. Activa el producto (`Activate`).

### 1.3 Crear la lista de License Testers
Play Console → Settings → License testing → agregar tu cuenta Gmail y las cuentas de QA.

Con esto las compras desde esos correos son **gratuitas** y se cancelan automáticamente cada 5 minutos (subs mensuales). Sin esto, te van a cobrar plata real al probar.

### 1.4 Crear el Service Account para que la Cloud Function consulte Google Play
1. Play Console → Settings → API access.
2. Vincular un proyecto de Google Cloud (puede ser el mismo de Firebase).
3. Crear un Service Account con rol **Service Account User** y permisos:
   - View financial data
   - Manage orders and subscriptions
4. Descargar el JSON de credenciales (lo necesitarás abajo).

### 1.5 Configurar Real-time Developer Notifications
1. Play Console → Monetize → Monetization setup → Real-time developer notifications.
2. Pegar el topic Pub/Sub que crea Firebase:
   ```
   projects/<tu-project-id>/topics/playRtdn
   ```
3. Test connection: si dice "Notification sent successfully", todo OK.

---

## 2. Firebase — Desplegar backend de billing

### 2.1 Plan Blaze (pay-as-you-go) obligatorio
Las Cloud Functions con dependencias externas (`googleapis`) **no funcionan en plan Spark/gratis**. Habilítalo en Firebase Console → Usage and billing → Modify plan.

### 2.2 Inicializar Firebase CLI
```powershell
npm install -g firebase-tools
firebase login
firebase init functions  # cuando pregunte, "Use existing files" para no sobrescribir
```

### 2.3 Subir las credenciales del Service Account como secret
```powershell
# Copia el contenido del JSON del Service Account a un archivo temporal y luego:
firebase functions:secrets:set GOOGLE_PLAY_CREDENTIALS
# Pega el JSON completo cuando lo pida.
```

### 2.4 Configurar variables del proyecto (opcional, los defaults ya funcionan)
```powershell
firebase functions:config:set `
  rutaalmacen.package="com.example.rutaalmacen" `
  rutaalmacen.productos="vendedor_mensual,comercio_mensual,empresarial_mensual"
```

### 2.5 Instalar dependencias y desplegar
```powershell
cd functions
npm install
cd ..
firebase deploy --only functions
```

Verifica que en Firebase Console → Functions aparezcan:
- `verificarCompraSuscripcion` (HTTPS Callable)
- `manejarNotificacionGooglePlay` (Pub/Sub trigger)

### 2.6 Crear el topic Pub/Sub para Real-time Notifications
La primera vez que despliegues `manejarNotificacionGooglePlay`, Firebase crea automáticamente el topic `playRtdn`. Si no, créalo manualmente:
```powershell
gcloud pubsub topics create playRtdn
```
Después dar permisos a Google Play:
```powershell
gcloud pubsub topics add-iam-policy-binding playRtdn `
  --member="serviceAccount:google-play-developer-notifications@system.gserviceaccount.com" `
  --role="roles/pubsub.publisher"
```

### 2.7 Desplegar reglas de Firestore endurecidas
```powershell
firebase deploy --only firestore:rules
```
Las nuevas reglas (`firestore.rules`) impiden que el cliente Android modifique los campos `plan`, `planVencimiento`, `ultimoPurchaseToken`, `compraVerificada` o `rol` directamente. Solo la Cloud Function (que usa Admin SDK) puede tocarlos.

### 2.8 Crear el índice compuesto que necesita el contador de alertas
Firebase Console → Firestore Database → Indexes → Add index:
- Collection: `Notificaciones_IA`
- Fields: `vendedorId` (Ascending) + `fechaCreacion` (Ascending)
- Query scope: Collection

(Sin esto el contador de "alertas hoy" igual funciona gracias al fallback en `PlanManager.contarAlertasHoy`, pero el índice es más eficiente.)

---

## 3. Antes de publicar — Llenar metadatos de Play Console

- [ ] **Política de privacidad** (URL pública).
- [ ] **Data Safety Form** — declarar que la app procesa: ubicación, audio, fotos, datos financieros (suscripciones).
- [ ] **App content** → Target audience, Ads, News app.
- [ ] **Store listing** → screenshots, video, descripción.
- [ ] **Pricing & distribution** → países disponibles.
- [ ] **Acceptable use** y **Subscription policies** confirmadas.

---

## 4. Cómo probar antes de pasar a producción

### 4.1 Test internal con License testers
1. Sube el AAB a Internal testing.
2. Invita a tu cuenta Gmail como tester.
3. Abre el link de opt-in de la app (Play Console → Internal testing → Copy link).
4. Instala desde Play Store en un teléfono con esa cuenta logueada.
5. Compra un plan → debería:
   - Mostrar la pantalla de Google Play con el precio simulado.
   - Tras "Comprar", ver el toast "¡Suscripción Vendedor activada!".
   - Refrescar el contador (ej. "5/80 productos") y poner el chip "Actual" en el plan correcto.
6. Cancelar desde Play Store → Suscripciones → tu app → Cancelar.
7. En 1-2 minutos, el callback Pub/Sub debería bajar el plan a gratis automáticamente.

### 4.2 Validación de seguridad
- Con la app abierta y un usuario logueado, intenta desde Firebase Console:
  - Editar manualmente el documento de Usuario y poner `plan: "empresarial"`.
  - El cliente lo ignora porque siempre relee desde server.
  - Las reglas deberían rechazar la escritura si la intentas desde una request de cliente (Firestore Console usa Admin SDK, por eso te deja).
- Verifica los logs:
  ```powershell
  firebase functions:log --only verificarCompraSuscripcion
  ```

---

## 5. Decisiones del diseño actual

| Decisión | Por qué |
|---|---|
| Todos los planes son para **un solo almacén** | Simplifica la lógica y se alinea con el modelo "una cuenta = un almacén". `permiteAlmacenesMultiples = false` en todos los planes. |
| El botón "Volver a gratis" abre Google Play | No se puede cancelar una suscripción de Google desde la app por política. Solo Google puede cancelarla. |
| El cliente **nunca** escribe el campo `plan` | Reglas de Firestore + Cloud Function = imposible falsificar el plan desde un APK modificado. |
| El cliente **no** cae a un fallback "asumamos que está pagado" si el servidor falla | Antes lo hacía y era una vulnerabilidad crítica: bloqueando la red, un atacante activaba Empresarial gratis. Ahora si el server falla, se guarda el purchase token y se reintenta en la próxima apertura. |
| `obfuscatedAccountId` viaja con cada compra | Vincula el purchase token al UID de Firebase Auth. La Cloud Function rechaza compras cuyo `obfuscatedAccountId` no coincida con el `uid` que llama la función. |
| `restaurarComprasExistentes` se llama al abrir la pantalla de planes | Garantiza que si reinstalas, cambias de teléfono o se cae la red durante la verificación, recuperes tu suscripción al volver. |

---

## 6. Qué cosas NO están todavía y son nice-to-have

- **Upgrade/downgrade entre planes pagados** (`setSubscriptionUpdateParams`): hoy si tienes Vendedor y quieres Comercio, Google rechazará la nueva compra con `ITEM_ALREADY_OWNED`. El código lo detecta y dispara restauración, pero idealmente debería ofrecer la migración con prorrateo.
- **Play Integrity API**: protección anti-tampering del APK. Gratis hasta 10.000 requests/día. Detecta APKs modificados que intenten llamar al backend.
- **Telemetría de billing en Crashlytics**: hoy los errores van solo a `Log.w` y un Toast. Idealmente cada `BillingResponseCode != OK` debería reportarse a Crashlytics para detectar problemas en producción.
- **Pantalla de "Compra en proceso"**: cuando el estado es `PENDING` (pagos en efectivo, gift cards) hay que mostrar al usuario "esperando confirmación" durante hasta 3 días.

---

## 7. Variables que tendrás que cambiar antes de publicar

| Archivo | Línea | Valor actual | Valor para producción |
|---|---|---|---|
| `app/build.gradle.kts` | 15 | `applicationId = "com.example.rutaalmacen"` | El packageName real que registres en Play Console |
| `functions/index.js` | 26 | `PACKAGE_NAME` defaulta a `com.example.rutaalmacen` | Cambiar default o configurar via env |
| `app/build.gradle.kts` | 18-19 | `versionCode = 1, versionName = "1.0"` | Incrementar en cada release |

---

## 8. Comandos rápidos de referencia

```powershell
# Compilar APK debug
.\gradlew.bat :app:assembleDebug

# Compilar AAB release firmado
.\gradlew.bat :app:bundleRelease

# Desplegar todo a Firebase
firebase deploy --only "functions,firestore:rules,firestore:indexes"

# Ver logs en vivo de la Cloud Function
firebase functions:log --only verificarCompraSuscripcion

# Rotar el secret de Google Play (si cambia el Service Account)
firebase functions:secrets:set GOOGLE_PLAY_CREDENTIALS

# Probar localmente la función con el emulador
cd functions; npm run serve
```
