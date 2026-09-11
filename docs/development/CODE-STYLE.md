# Guía de estilo de código

Esta guía prioriza que el código sea sencillo de leer, revisar y modificar tanto por personas como
por agentes de IA. La referencia principal es el
[estilo del Spring Framework](https://github.com/spring-projects/spring-framework/wiki/Code-Style),
complementado por [Google Java Style](https://google.github.io/styleguide/javaguide.html) cuando la
guía de Spring no cubre un caso. Las reglas específicas de este repositorio prevalecen si hay una
diferencia.

## Convenciones del repositorio

- Usar UTF-8, finales de línea LF, una línea nueva al final del archivo y ningún espacio al final de
  línea.
- En Java, usar tabulaciones para la sangría estructural, mostradas con ancho 4. En XML, YAML, JSON
  y properties, usar dos espacios; en Python, cuatro.
- Procurar líneas de hasta 100 caracteres. El linter advierte al superar 120; los imports y nombres
  inevitables pueden ser excepciones revisadas conscientemente.
- Usar una sola línea en blanco entre campos, métodos y secciones lógicas. Dentro de un método,
  separar únicamente pasos conceptuales; no interrumpir una cadena fluida o un bloque corto sin
  motivo.
- No comprimir declaraciones, sentencias o cuerpos de control para ahorrar líneas. Usar llaves aun
  cuando `if`, `for`, `while` o `do` tengan una sola sentencia.
- Dividir constructores, records, llamadas y cadenas fluidas cuando una línea larga obligue a leer
  horizontalmente. Mantener juntos los elementos que expresan una sola idea.
- Evitar imports con `*`. Agrupar imports estáticos, Java/Jakarta, terceros y código del proyecto,
  sin líneas vacías dentro de un mismo grupo.
- Usar nombres descriptivos. Abreviaturas cortas como `db`, `id` o `tx` solo son apropiadas cuando
  su significado es inequívoco en el contexto inmediato.
- En Spring, preferir inyección por constructor y mantener controllers, casos de uso, dominio y
  adaptadores separados según la arquitectura existente. El formato no debe ocultar cambios de
  comportamiento ni mezclar responsabilidades.

## Ayudas opcionales

`.editorconfig` permite que los editores compatibles apliquen las reglas básicas al guardar. No se
configuran hooks ni reformateo automático.

Checkstyle se ejecuta durante `mvn verify` y también puede invocarse con:

```text
mvn -B -ntp checkstyle:check
```

Sus hallazgos son advertencias: no hacen fallar el build. Deben revisarse y corregirse cuando mejoren
la lectura; una excepción intencional debe seguir siendo comprensible sin desactivar reglas de forma
global. La opción `failOnViolation=false` está documentada por el
[Maven Checkstyle Plugin](https://maven.apache.org/plugins/maven-checkstyle-plugin/check-mojo.html#failOnViolation).

Para una normalización manual amplia se puede ejecutar el formateador oficial, revisar el diff y
ajustar luego los cortes semánticos:

```text
mvn -B -ntp io.spring.javaformat:spring-javaformat-maven-plugin:0.0.47:apply
```

El formateador no está enlazado al ciclo de build ni se aplica automáticamente. Consulte
[Spring Java Format](https://github.com/spring-io/spring-javaformat) para integración opcional con el
IDE y limitaciones de la herramienta.

## Lista breve de revisión

1. Leer el diff para confirmar que un cambio de formato no alteró comportamiento.
2. Revisar líneas largas, bloques demasiado compactos y espacios verticales excesivos.
3. Ejecutar la verificación específica del módulo y después `mvn -B -ntp clean verify`.
4. Separar los futuros cambios de formato masivo de los cambios funcionales siempre que sea posible.

