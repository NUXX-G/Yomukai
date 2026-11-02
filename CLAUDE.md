# Instrucciones para agentes

Yomukai es una app Android en Kotlin con Compose. Lee `PLAN.md` antes de empezar: dice qué está hecho y qué no.

## Reglas

- Trabaja siempre en una rama `colmena/<tema>`. Nunca en `main`.
- En esta máquina no hay JDK ni SDK de Android: **no intentes ejecutar Gradle ni instalarlos**. Gastarías cuota sin resultado.
- Compila GitHub Actions al publicar. Por eso revisa bien imports, tipos y nombres antes de terminar.
- Haz cambios pequeños y de un solo tema, para que un fallo de compilación sea fácil de encontrar.
- Si cambias un parser, añade o ajusta su prueba en `app/src/test`.
- Respeta el estilo del código que ya existe. Una fuente es una clase pequeña que implementa una interfaz.
- Actualiza `PLAN.md` al cerrar una tarea.
