## Run 2 (regresión)

| Clase | Pruebas | Aprobadas | Fallidas | Omitidas | Tiempo (s) |
|---|---:|---:|---:|---:|---:|
| AUT01LoginValidacionTest | 9 | 9 | 0 | 0 | 0.25 |
| AUT02PedidoValidacionTest | 7 | 6 | 1 | 0 | 0.02 |
| AUT03ReintentosExcepcionesTest | 8 | 8 | 0 | 0 | 0.63 |
| AUT04LoginIntegracionTest | 7 | 7 | 0 | 0 | 8.47 |
| AUT05PedidosApiRealTest | 5 | 0 | 0 | 5 | 0.02 |
| AUT06PersistenciaTest | 6 | 6 | 0 | 0 | 11.97 |
| ExampleUnitTest | 1 | 1 | 0 | 0 | 0.00 |
| OrderRepositoryTest | 6 | 6 | 0 | 0 | 0.21 |
| P12ResilienciaTest | 14 | 14 | 0 | 0 | 8.39 |
| PedidosApiIntegracionTest | 4 | 0 | 0 | 4 | 0.00 |
| ValidadorAccesoTest | 11 | 11 | 0 | 0 | 0.00 |
| ValidadorPedidoTest | 7 | 6 | 1 | 0 | 0.02 |
| WB01ValidarAccesoTest | 15 | 15 | 0 | 0 | 0.01 |
| WB02ParsePedidoTest | 7 | 7 | 0 | 0 | 0.20 |
| WB03PeticionTest | 16 | 16 | 0 | 0 | 24.58 |
| **Total** | **123** | **112** | **2** | **9** | **54.77** |

❌ Pruebas fallidas:
- `AUT02PedidoValidacionTest.telefono_con_9_u_11_digitos_o_letras_se_rechaza`: java.lang.AssertionError: Teléfono: 442123456 expected:<Debe tener 10 dígitos> but was:<null>
- `ValidadorPedidoTest.telefono_de_10_digitos`: java.lang.AssertionError
