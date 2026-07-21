# Política de Privacidad - RutaAlmacén

**Última actualización:** 20 de julio de 2026

**Responsable del tratamiento:** [Tu nombre completo / Razón social]
**Correo de contacto:** carloscancino010@gmail.com
**País:** Chile

---

## 1. Introducción

La presente Política de Privacidad describe cómo RutaAlmacén ("la Aplicación") recopila, utiliza, almacena y protege los datos personales de sus usuarios, en cumplimiento de:

- **Ley N° 19.628** sobre Protección de la Vida Privada (Chile), modificada por la **Ley N° 21.727** (2024)
- **Reglamento General de Protección de Datos (GDPR)** de la Unión Europea
- **Políticas de datos de Google Play Store**

Al utilizar la Aplicación, usted acepta las prácticas descritas en esta política.

---

## 2. Datos personales que recopilamos

### 2.1 Datos proporcionados directamente por el usuario

| Dato | Finalidad | Base legal |
|------|-----------|------------|
| Nombre completo | Identificación en la plataforma | Consentimiento |
| Correo electrónico | Autenticación y comunicación | Consentimiento / Ejecución contractual |
| Fotografía de perfil | Identificación visual | Consentimiento |
| Rol (comprador/vendedor) | Funcionalidad diferenciada | Ejecución contractual |
| Datos de inventario y productos | Operación del servicio | Ejecución contractual |
| Notas personales del vendedor | Funcionalidad privada | Ejecución contractual |
| Ubicación geográfica | Búsqueda de almacenes cercanos | Consentimiento explícito |
| Audio (voz) | Entrada de datos por voz | Consentimiento explícito |
| Imágenes de boletas | Entrada de datos por OCR | Consentimiento explícito |

### 2.2 Datos recopilados automáticamente

| Dato | Finalidad | Base legal |
|------|-----------|------------|
| Identificador de dispositivo (UID Firebase) | Autenticación | Ejecución contractual |
| Datos de uso y navegación | Mejora del servicio | Interés legítimo |
| Información de compras/suscripciones | Gestión de pagos | Ejecución contractual |
| Token de integridad del dispositivo | Seguridad anti-fraude | Interés legítimo |

### 2.3 Datos de terceros

| Fuente | Dato | Finalidad |
|--------|------|-----------|
| Google Sign-In | Nombre, correo, foto, token de autenticación | Autenticación |
| Google Play Billing | Tokens de compra, estado de suscripción | Gestión de pagos |

---

## 3. Finalidades del tratamiento

Los datos personales se tratan para:

1. **Autenticación:** Verificar la identidad del usuario mediante Google Sign-In
2. **Prestación del servicio:** Permitir la gestión de inventario, búsqueda de almacenes y comunicación entre compradores y vendedores
3. **Procesamiento de pagos:** Gestionar suscripciones mediante Google Play Billing
4. **Seguridad:** Detectar dispositivos comprometidos, prevenir fraude y proteger la integridad de la plataforma
5. **Mejora del servicio:** Analizar patrones de uso para optimizar funcionalidades
6. **Notificaciones:** Enviar alertas relevantes sobre la actividad del usuario

---

## 4. Base legal para el tratamiento (Ley 21.727)

De acuerdo con la Ley 21.727, las bases legales son:

- **Consentimiento explícito:** Para ubicación, cámara, micrófono y datos sensibles
- **Ejecución de contrato:** Para datos necesarios para la prestación del servicio
- **Interés legítimo:** Para seguridad y prevención de fraude
- **Obligación legal:** Cuando sea requerido por autoridad competente

---

## 5. Almacenamiento y transferencia internacional

### 5.1 Almacenamiento

- **Firebase Authentication:** Estados Unidos (Google LLC)
- **Cloud Firestore:** Estados Unidos (Google LLC)
- **Firebase Storage:** Estados Unidos (Google LLC)
- **Datos locales del dispositivo:** Cifrados con AES-256-GCM en el almacenamiento interno

### 5.2 Transferencia internacional

Los datos se transfieren a servidores de Google LLC en Estados Unidos. Esta transferencia se ampara en:
- Cláusulas Contractuales Tipo (SCC) aprobadas por la Comisión Europea
- Certificación Privacy Shield (si aplica)
- Políticas de privacidad de Google Cloud

---

## 6. Plazo de conservación

| Tipo de dato | Plazo |
|--------------|-------|
| Cuenta de usuario | Mientras la cuenta esté activa |
| Datos de inventario | Mientras el vendedor mantenga su cuenta |
| Historial de compras | 5 años (obligación legal tributaria) |
| Notas personales | Hasta eliminación por el usuario |
| Datos de ubicación | Solo durante el uso de la funcionalidad |
| Tokens de sesión | Hasta cierre de sesión o expiración |

---

## 7. Derechos del titular (Ley 21.727 - Art. 10 y siguientes)

De acuerdo con la Ley 21.727, usted tiene derecho a:

### 7.1 Derechos ARCO-PD

- **Acceso:** Conocer qué datos personales se tratan
- **Rectificación:** Corregir datos inexactos o incompletos
- **Cancelación:** Solicitar eliminación de sus datos
- **Oposición:** Oponerse al tratamiento de sus datos
- **Portabilidad:** Recibir sus datos en formato estructurado
- **De no ser sometido a decisiones automatizadas:** Derecho a revisión humana

### 7.2 Cómo ejercer sus derechos

Envíe su solicitud a: **carloscancino010@gmail.com**

Debe incluir:
- Nombre completo del titular
- Documento de identidad
- Descripción clara del derecho que ejerce
- Fecha y firma

**Plazo de respuesta:** 15 días hábiles (según Ley 21.727)

### 7.3 Reclamación ante la autoridad

Si considera que sus derechos no han sido atendidos, puede reclamar ante la **Agencia de Protección de Datos Personales** (una vez constituida) o ante los tribunales competentes.

---

## 8. Derechos GDPR (usuarios de la Unión Europea)

Si usted se encuentra en la UE, tiene derechos adicionales:

- **Derecho al olvido:** Eliminación completa de sus datos
- **Derecho a la limitación del tratamiento**
- **Derecho a retirar el consentimiento** en cualquier momento
- **Derecho a presentar reclamación** ante una autoridad de control

Para ejercer estos derechos, contacte: **carloscancino010@gmail.com**

---

## 9. Seguridad de los datos

### 9.1 Medidas técnicas implementadas

- **Cifrado en tránsito:** TLS 1.3 con certificate pinning
- **Cifrado en reposo:** AES-256-GCM para datos locales
- **Ofuscación de código:** R8/ProGuard para prevenir ingeniería inversa
- **Detección de amenazas:** Play Integrity API, detección de root/debugging
- **Protección contra screenshots:** FLAG_SECURE en pantallas sensibles
- **Reglas de seguridad:** Firestore Security Rules con control de roles

### 9.2 Medidas organizativas

- Acceso mínimo necesario a los datos
- Auditorías periódicas de seguridad
- Actualizaciones de seguridad regulares

---

## 10. Menores de edad

La Aplicación **NO** está dirigida a menores de 14 años. Si usted es padre o tutor y detecta que un menor ha proporcionado datos personales, contacte a **carloscancino010@gmail.com** para su eliminación.

---

## 11. Notificación de brechas de seguridad

En caso de brecha de seguridad que afecte datos personales:

- **Plazo de notificación:** 72 horas desde el conocimiento del incidente
- **Autoridad competente:** Agencia de Protección de Datos Personales (Chile)
- **Afectados:** Notificación directa si hay alto riesgo para sus derechos
- **Medidas:** Contención, evaluación y remediación inmediata

---

## 12. Cambios en la Política de Privacidad

Esta política puede ser actualizada. Los cambios serán notificados mediante:

- Aviso en la aplicación
- Correo electrónico al usuario registrado
- Actualización de la fecha de "Última actualización"

El uso continuado de la Aplicación implica aceptación de los cambios.

---

## 13. Contacto

**Responsable del tratamiento:**
- Correo: carloscancino010@gmail.com
- País: Chile

**Delegado de Protección de Datos (DPO):**
- Correo: carloscancino010@gmail.com

---

## 14. Aceptación

Al registrarse y utilizar RutaAlmacén, usted declara:

1. Haber leído y comprendido esta Política de Privacidad
2. Otorgar su consentimiento libre, informado y explícito para el tratamiento de sus datos
3. Ser mayor de 14 años
4. Entender que puede ejercer sus derechos en cualquier momento

---

*Este documento fue generado el 20 de julio de 2026 y está sujeto a revisión legal profesional.*
