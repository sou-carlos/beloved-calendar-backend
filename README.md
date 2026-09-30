# Beloved — API de contas

Java 25, Spring Boot 4.1.1, Spring Security, PostgreSQL 17 e Flyway.
Adaptado da estrutura de autenticação do Shiny Path, com banco, cookies e credenciais independentes.

## Executar com Docker

Crie `.env` a partir de `.env.example` e defina `DB_PASSWORD`. Na instalação local inicial, `.env` já foi criado com senha aleatória. Nunca versionar esse arquivo.

```powershell
docker compose up --build -d
docker compose logs -f backend
```

API: http://localhost:8081. PostgreSQL: localhost:5433, banco `beloved_calendar`.
As portas diferem das do projeto de referência para evitar conflitos. O volume `postgres-data` mantém as contas entre reinicializações.

No frontend `C:/Users/carlo/beloved-calendar`, execute `npm run dev`. Vite encaminha `/api` para `http://127.0.0.1:8081`, tanto no desenvolvimento como no preview.

## Executar Java localmente

Inicie apenas o banco com `docker compose up -d postgres`. Configure `JAVA_HOME` para o JDK 25 e as variáveis `DB_PASSWORD` e `DB_USERNAME` com os valores locais do `.env` (Spring não lê esse arquivo automaticamente). Execute `./mvnw.cmd spring-boot:run`.

## API

| Método | Rota | Resultado |
| --- | --- | --- |
| GET | `/api/auth/csrf` | Token CSRF e nome do header |
| POST | `/api/auth/register` | Cria conta e inicia sessão (201) |
| POST | `/api/auth/login` | Inicia sessão (200) |
| GET | `/api/auth/me` | Perfil autenticado ou 401 |
| POST | `/api/auth/logout` | Encerra sessão (204) |

Cadastro recebe `{ "name": "Marina", "email": "marina@example.com", "password": "senha-com-8-caracteres" }`. Login recebe email e password. Nome: 2–80 caracteres; email: até 254; senha: 8–128 no cadastro. Email é normalizado. Todos os POST exigem o token obtido de `/csrf` no header indicado. Enviar cookies (`credentials: include`) e buscar novo token após login/logout. O frontend já faz isso automaticamente.

Senhas usam PBKDF2; nunca são retornadas. Cookie `BELOVED_SESSION` HttpOnly e SameSite=Lax, sessão de 8 horas, troca de ID no login e invalidação no logout. Sessões ficam na memória da API: reiniciar o backend exige novo login, sem apagar contas. CORS permite apenas origens configuradas. Migrações Flyway criam o schema; Hibernate apenas o valida.

Erros retornam `{ code, message, fieldErrors? }`: 400 validação, 401 credenciais/sessão, 403 CSRF/CORS, 409 email duplicado. Amigos e presentes agora possuem sincronização por conta; os dados de visitante continuam apenas no navegador. Veja a seção de sincronização abaixo.

## Testes

```powershell
./mvnw.cmd -B -ntp verify
```

Os testes iniciam PostgreSQL temporário (sem Docker), aplicam a migração e verificam cadastro, hash, normalização, sessão/cookie, login/logout, CSRF, CORS e erros. Não usam o banco local do Compose.

## Produção

Publicar frontend e `/api` na mesma origem por proxy HTTPS. Definir `COOKIE_SECURE=true`, `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` e `APP_ALLOWED_ORIGINS` para o domínio real. O proxy deve encaminhar `/api` antes do fallback da SPA. O Compose fornecido é para desenvolvimento local, com portas vinculadas a 127.0.0.1. Para instalações em origens diferentes, `VITE_API_URL` aceita a URL da API, mas cookies SameSite=Lax requerem que ambos pertençam ao mesmo site. Não há publicação automática neste projeto.

Referência: https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html

## Sincronização offline

`GET /api/sync?accountId=UUID` retorna `{ accountId, records }`. Cada registro tem `id`, `version` e `person` (null indica exclusão). A primeira versão usa um snapshot completo por conta, adequado ao volume de um calendário pessoal; não há paginação/cursor nesta etapa.

`POST /api/sync` recebe `{ accountId, operationId, id, baseVersion, person }`, com CSRF. Cada amigo e seus presentes formam uma unidade versionada. Campos pessoais são validados; IDs novos são UUID e IDs legados de até 100 caracteres são preservados. A conta declarada deve coincidir com a sessão, inclusive ao trocar de conta em outra aba.

Resposta HTTP 200: `{ accountId, operationId, status: "accepted" | "conflict", record }`. O cliente só descarta uma alteração após confirmar seu identificador de operação. Resultados são registrados em `calendar_operations`: repetir a mesma operação retorna o mesmo resultado, sem incrementar a versão; reutilizar o ID com conteúdo diferente retorna 409. Uma trava por usuário serializa escritas concorrentes, enquanto `baseVersion` impede sobrescritas de dados antigos.

`calendar_records` contém o documento validado do amigo (incluindo presentes) em JSONB, versão e marcador permanente de exclusão. Os marcadores e recibos não são removidos automaticamente: assim um navegador que passou muito tempo offline não recria um registro excluído. Uma futura política de retenção deve incluir expiração explícita de clientes e ressicronização completa, antes de remover esses registros.

No navegador, visitante usa o banco legado; cada conta usa seu próprio IndexedDB. Perfil local em localStorage serve apenas para selecionar a cópia offline, nunca para autenticar a API. Fila e mudanças são gravadas na mesma transação local. Ao encerrar a sessão, a interface volta ao visitante; filas da conta permanecem guardadas. Em dispositivo compartilhado, encerrar a sessão antes de entregar o navegador. O cache local não é criptografado.

A importação é opcional, copia os dados de visitante e preserva IDs. Repeti-la não sobrescreve registros já conhecidos nem recria excluídos. Conflitos preservam as versões e exigem escolha explícita; é possível manter a versão da conta e criar uma nova cópia da local. Alterações em registros diferentes não entram em conflito.

Sincronização ao abrir, salvar, recuperar conexão, voltar à janela e a cada 15 segundos enquanto a página está visível; também há botão manual. Falhas mantêm a fila; rejeições de validação não bloqueiam outros amigos. Não há sincronização garantida com o app fechado.

Testes: `mvnw.cmd verify` inclui concorrência, repetição, conflitos, exclusão e isolamento. No frontend, `npm run test:e2e` exercita múltiplos navegadores, importação, reconexão e cache offline.
