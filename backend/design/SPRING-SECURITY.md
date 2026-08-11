# Spring & Spring Security — Notes de référence

Compilation d'une session de questions/réponses couvrant la configuration Spring
(`@Configuration`, `@ConfigurationProperties`) et la sécurité (`SecurityFilterChain`,
filtres custom, authentification JWT). L'ordre suit une progression du plus général
au plus concret.

---

## 1. `@Configuration`

### Qu'est-ce que c'est ?

`@Configuration` marque une classe comme **source de définitions de beans** pour le
conteneur Spring (IoC container). Elle indique : « cette classe contient des méthodes
qui produisent des objets que Spring doit gérer ». Ces méthodes sont annotées `@Bean`,
et chaque objet retourné est enregistré dans le contexte Spring, prêt à être injecté.

```java
@Configuration
public class AppConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }
}
```

Spring appelle ces méthodes au démarrage et conserve les objets comme beans singletons
réutilisables partout dans l'application.

### L'intérêt

Configurer **par le code** (Java Config) plutôt que par XML : plus lisible, typé,
vérifié à la compilation. Cas d'usage principaux :

- **Instancier des classes qu'on ne possède pas** (bibliothèques tierces sur lesquelles
  on ne peut pas mettre `@Component`, ex. `RestTemplate`).
- **Centraliser la construction d'objets complexes** (plusieurs étapes de config).
- **Choisir dynamiquement une implémentation** selon l'environnement, via `@Profile`
  ou `@Conditional`.

### Le point subtil : la « garantie singleton »

C'est ce qui distingue vraiment `@Configuration` d'une classe avec de simples méthodes
`@Bean`.

```java
@Configuration
public class AppConfig {

    @Bean
    public ServiceA serviceA() {
        return new ServiceA(commonDependency());
    }

    @Bean
    public ServiceB serviceB() {
        return new ServiceB(commonDependency());
    }

    @Bean
    public CommonDependency commonDependency() {
        return new CommonDependency();
    }
}
```

`commonDependency()` est appelée deux fois. Naïvement on attendrait deux instances.
Mais Spring génère une **sous-classe proxy (CGLIB)** de `AppConfig` qui intercepte ces
appels : les deux services reçoivent **la même** instance de `CommonDependency`
(sémantique singleton respectée). C'est le mode **« full »**.

Avec `@Bean` dans une classe `@Component` (ou sans `@Configuration`), on est en mode
**« lite »** : pas de proxy, l'appel direct `commonDependency()` créerait deux instances
distinctes.

Depuis Spring 5.2+, on peut désactiver le proxy :

```java
@Configuration(proxyBeanMethods = false)
```

Léger gain au démarrage, **uniquement** si les méthodes `@Bean` ne s'appellent pas
entre elles.

### Bonne pratique : injection par paramètre

Plutôt que l'appel direct de méthode (qui dépend du proxy), passer par les paramètres
rend la dépendance explicite :

```java
@Configuration
public class AppConfig {

    @Bean
    public CommonDependency commonDependency() {
        return new CommonDependency();
    }

    @Bean
    public ServiceA serviceA(CommonDependency dep) {   // injecté par Spring
        return new ServiceA(dep);
    }
}
```

### À savoir avec Spring Boot

`@SpringBootApplication` inclut déjà `@Configuration` (via `@SpringBootConfiguration`).
Les classes de config additionnelles sont auto-détectées si elles sont dans le package
scanné (celui de la classe principale ou ses sous-packages).

### `@Configuration` vs `@Component`

| Annotation | Déclare | Quand l'utiliser |
|---|---|---|
| `@Component` / `@Service` / `@Repository` | **un seul** bean (la classe elle-même), via component scan | pour **tes propres** classes |
| `@Configuration` + `@Bean` | **plusieurs** beans via ses méthodes | pour **assembler** des objets ou déclarer des composants **tiers** |

---

## 2. `@ConfigurationProperties` et `@ConfigurationPropertiesScan`

### Le problème

Une classe `@ConfigurationProperties` n'est **pas** un bean par le simple fait
d'exister. Il faut l'enregistrer **et** lui appliquer le *binding* des propriétés
(remplissage depuis `application.yml` / `.properties`). `@Configuration` ne fait pas
ça toute seule : elle ne parcourt pas le code à la recherche de classes
`@ConfigurationProperties`.

```java
@ConfigurationProperties(prefix = "app.mail")
public class MailProperties {
    private String host;
    private int port;
    // getters/setters
}
```

Ce POJO reste inerte tant que personne ne l'enregistre.

### Les trois façons de l'enregistrer

**1. `@Component` sur la classe** — récupérée par le component scan.

```java
@Component
@ConfigurationProperties(prefix = "app.mail")
public class MailProperties { ... }
```

**2. `@EnableConfigurationProperties(...)`** — on liste explicitement chaque classe.

```java
@Configuration
@EnableConfigurationProperties(MailProperties.class)
public class MailConfig { ... }
```

**3. `@ConfigurationPropertiesScan`** — scanne le package et enregistre
**automatiquement toutes** les classes `@ConfigurationProperties`, sans `@Component`
ni liste à maintenir.

```java
@SpringBootApplication
@ConfigurationPropertiesScan
public class MyApplication { ... }
```

### Le vrai argument : le constructor binding

Avec le *constructor binding*, on peut avoir des classes immuables (souvent des `record`) :

```java
@ConfigurationProperties(prefix = "app.mail")
public record MailProperties(String host, int port) {}
```

Une telle classe **ne peut pas** être enregistrée par `@Component` : le component scan
suppose un constructeur sans argument + injection par setters, incompatible avec
l'immuabilité. Restent `@EnableConfigurationProperties` ou `@ConfigurationPropertiesScan`.
Sur un projet avec beaucoup de classes de propriétés, le scan évite de maintenir une
longue liste.

### En résumé

- `@Configuration` = « voici des méthodes qui fabriquent des beans ».
- `@ConfigurationPropertiesScan` = « trouve mes classes `@ConfigurationProperties`,
  enregistre-les et injecte-leur les valeurs de config ».

La première ne remplace pas la seconde : elle ne détecte pas ces POJO ni ne déclenche
leur binding.

> **Piège :** mélanger `@Configuration` et `@ConfigurationProperties` sur la **même**
> classe fonctionne mais est déconseillé (la classe est proxifiée par CGLIB, ça brouille
> les responsabilités). Garder les classes de propriétés séparées des classes de config.

---

## 3. `@EnableMethodSecurity`

### Le rôle

Active la **sécurité au niveau des méthodes**. Là où la sécurité « classique » filtre
les requêtes HTTP par URL (via `SecurityFilterChain`), la sécurité par méthode protège
directement des méthodes de services/contrôleurs avec des annotations.

```java
@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    // ...
}
```

```java
@Service
public class DocumentService {

    @PreAuthorize("hasRole('ADMIN')")
    public void deleteDocument(Long id) { ... }

    @PreAuthorize("#userId == authentication.name")
    public Document getMyDocument(String userId) { ... }

    @PostAuthorize("returnObject.owner == authentication.name")
    public Document findById(Long id) { ... }
}
```

- `@PreAuthorize` — évalué **avant** l'exécution ; si faux, la méthode n'est jamais
  appelée (`AccessDeniedException`).
- `@PostAuthorize` — évalué **après**, utile quand la décision dépend de l'objet
  retourné (`returnObject`).
- `@PreFilter` / `@PostFilter` — filtrent les éléments d'une collection en entrée/sortie.

Les expressions SpEL (`hasRole`, `#param`, `authentication`, `returnObject`…) permettent
des règles fines et contextuelles, hors de portée d'une sécurité par URL.

### Fonctionnement technique

Basé sur l'**AOP** : Spring crée un proxy autour des beans sécurisés et intercepte les
appels pour évaluer les règles avant/après.

> **Piège (comme `@Transactional`) :** un **appel interne** (méthode du bean qui en
> appelle une autre du même bean via `this`) ne passe pas par le proxy → la sécurité
> **n'est pas déclenchée**.

### Contexte historique

Introduite en **Spring Security 5.6**, elle remplace `@EnableGlobalMethodSecurity`
(dépréciée). Deux différences pratiques :

- **`prePostEnabled = true` par défaut** : `@PreAuthorize` marche sans rien préciser
  (l'ancienne annotation exigeait `@EnableGlobalMethodSecurity(prePostEnabled = true)`).
- **Moteur `AuthorizationManager`** plus modulaire, remplaçant l'ancien système de
  voters / `AccessDecisionManager`.

Options désactivées par défaut mais activables :

```java
@EnableMethodSecurity(
    securedEnabled = true,   // active @Secured
    jsr250Enabled = true     // active @RolesAllowed (JSR-250)
)
// prePostEnabled = true est déjà le défaut
```

---

## 4. `SecurityFilterChain`

### Le rôle

Bean central qui définit **comment les requêtes HTTP entrantes sont sécurisées** :
quelles URL sont publiques, lesquelles exigent une authentification, type de login,
CSRF, sessions, etc.

### La chaîne de filtres

Spring Security fonctionne comme une **chaîne de filtres servlet** placée devant
l'application. Chaque requête traverse une série de filtres à responsabilité précise
(authentification, autorisation, CSRF, logout…). Un bean `SecurityFilterChain`
représente **une** de ces chaînes : il associe un ensemble de requêtes (un *matcher*)
à la liste ordonnée de filtres appliqués.

### Usage typique (Spring Security 5.7+)

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/public/**", "/login").permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .formLogin(form -> form.loginPage("/login").permitAll())
            .logout(logout -> logout.permitAll())
            .csrf(Customizer.withDefaults());

        return http.build();
    }
}
```

`HttpSecurity` sert de *builder* ; `http.build()` produit le `SecurityFilterChain`
enregistré comme bean.

### Changement de style (ancien code)

Avant 5.7, on étendait `WebSecurityConfigurerAdapter` et on surchargeait
`configure(HttpSecurity http)`. Cette classe est **dépréciée en 5.7** puis **supprimée
en Spring Security 6**. On passe d'un modèle par **héritage** à un modèle par
**composition** (déclaration de beans), plus flexible.

### Atout : plusieurs chaînes

Étant un bean, on peut en déclarer **plusieurs**, chacune ciblant des URL différentes
via `securityMatcher` — typiquement une API REST *stateless* et une interface web
*stateful* :

```java
@Bean
@Order(1)
public SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
    http
        .securityMatcher("/api/**")
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .httpBasic(Customizer.withDefaults());
    return http.build();
}

@Bean
@Order(2)
public SecurityFilterChain webChain(HttpSecurity http) throws Exception {
    http
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
        .formLogin(Customizer.withDefaults());
    return http.build();
}
```

Les chaînes sont évaluées dans l'ordre `@Order` : la **première** dont le matcher
correspond s'applique (les suivantes sont ignorées pour cette requête). D'où
l'importance de placer les matchers les plus spécifiques en premier.

### Lien avec `@EnableMethodSecurity`

Complémentaires : `SecurityFilterChain` = sécurité **par requête / URL** (première ligne
de défense, niveau filtre HTTP) ; sécurité par méthode = **au plus près de la logique
métier**. Défense en profondeur.

---

## 5. Injection de `HttpSecurity`

Dans `filterChain(HttpSecurity http)`, `HttpSecurity` est injecté automatiquement par
Spring (même mécanisme que l'injection par paramètre de méthode `@Bean`). Mais ce n'est
pas un bean ordinaire.

### Scope `prototype`

Si on déclare **plusieurs** `SecurityFilterChain`, chaque méthode reçoit sa **propre**
instance de `HttpSecurity`, fraîche et indépendante. C'est nécessaire : `HttpSecurity`
est un *builder* mutable et à état ; une instance partagée verrait la config d'une
chaîne contaminer l'autre, et `build()` ne peut être appelé qu'une fois par builder.
`HttpSecurity` est donc en scope **`prototype`** : nouvelle instance à chaque injection.

### Déjà pré-configuré

L'instance injectée n'est pas vierge : Spring applique en amont des valeurs par défaut
(via des `HttpSecurityConfigurer`) — accès à l'`AuthenticationManager`, réglages de base.

### Injecté vs produit

- `HttpSecurity` est **injecté** (Spring le fournit) → c'est l'**outil / builder**.
- `SecurityFilterChain` est **retourné** par la méthode (via `http.build()`) → c'est le
  **produit fini**, et c'est *lui* le bean enregistré.

---

## 6. Exemple : `CorrelationIdFilter`

Filtre de corrélation : attache un **identifiant unique à chaque requête HTTP** pour
suivre cette requête à travers tous ses logs (et à travers plusieurs microservices).

```java
@Component
public final class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    static final Pattern VALID_VALUE_PATTERN = Pattern.compile("^[A-Za-z0-9_.:-]{1,128}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String value = resolve(request.getHeader(HEADER_NAME));
        MDC.put(MDC_KEY, value);
        response.setHeader(HEADER_NAME, value);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static String resolve(String inbound) {
        if (inbound != null && VALID_VALUE_PATTERN.matcher(inbound).matches()) {
            return inbound;
        }
        return UUID.randomUUID().toString();
    }
}
```

### Points clés

- **`@Component`** : Spring Boot détecte les beans de type `Filter` et les insère dans
  la chaîne.
- **`OncePerRequestFilter`** : garantit une exécution **exactement une fois par requête**
  (évite les doubles passages sur forwards, dispatch async, ERROR dispatch). On
  n'implémente que `doFilterInternal`.
- **Le `Pattern` de validation** : accepte alphanumérique + `_ . : -`, 1 à 128
  caractères. Deux objectifs :
    1. **Compatibilité** avec AWS X-Ray, segments hex OpenTelemetry, UUID.
    2. **Sécurité** : rejeter espaces, caractères de contrôle et surtout `\r\n` (CRLF)
       bloque l'**injection de log / HTTP response splitting** — la valeur étant réécrite
       dans un header de réponse et dans les logs.
- **`MDC` (Mapped Diagnostic Context)** : contexte de log attaché au **thread courant**
  (SLF4J/Logback/Log4j2). Ses valeurs s'insèrent automatiquement dans chaque ligne de
  log via le pattern (ex. `%X{correlationId}`), sans passer l'ID manuellement.
- **`response.setHeader(...)`** : renvoie l'ID au client.
- **`chain.doFilter(...)`** : tout le traitement se déroule « à l'intérieur » de cet
  appel, pendant que le MDC est peuplé.
- **Le `finally` — crucial** : les serveurs recyclent les threads via un **pool**. Le
  MDC étant lié au thread, sans nettoyage la requête suivante servie par ce thread
  hériterait de l'ancien ID (fuite de contexte, corruption des logs, risque de
  confidentialité). `finally` garantit le nettoyage même en cas d'exception.
- **`resolve(...)`** : réutilise un ID entrant **valide** (→ corrélation inter-services)
  sinon **génère** un UUID. `static` car fonction pure de son entrée.

---

## 7. Chaîne de filtres complète (exemple réel)

```java
@Bean
public SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        CorrelationIdFilter correlationIdFilter,
        RateLimitFilter rateLimitFilter,
        JwtAuthenticationFilter jwtAuthenticationFilter,
        ApiKeyAuthenticationFilter apiKeyAuthenticationFilter,
        ForcedPasswordChangeFilter forcedPasswordChangeFilter,
        AuthenticationEntryPoint authenticationEntryPoint,
        AccessDeniedHandler accessDeniedHandler) throws Exception {

    String apiPrefix = stripTrailingSlash(properties.api().basePath());
    String loginPath = apiPrefix + "/auth/login";
    String adminPattern = apiPrefix + "/admin/**";
    String agentsPattern = apiPrefix + "/agents/**";
    String conversationsPattern = apiPrefix + "/conversations/**";
    String apiPattern = apiPrefix + "/**";

    return http
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                    .requestMatchers(HttpMethod.POST, loginPath).permitAll()
                    .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                    .requestMatchers(adminPattern).hasRole("ADMIN")
                    .requestMatchers(agentsPattern).hasAnyRole("STANDARD", "ADMIN")
                    .requestMatchers(conversationsPattern).hasAnyRole("STANDARD", "ADMIN", "SYSTEM")
                    .requestMatchers(apiPattern).authenticated()
                    .anyRequest().authenticated())
            .exceptionHandling(eh -> eh
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(rateLimitFilter, JwtAuthenticationFilter.class)
            .addFilterBefore(correlationIdFilter, RateLimitFilter.class)
            .addFilterAfter(apiKeyAuthenticationFilter, JwtAuthenticationFilter.class)
            .addFilterAfter(forcedPasswordChangeFilter, ApiKeyAuthenticationFilter.class)
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable())
            .build();
}
```

### Le préambule : les chemins

`properties` (un bean `@ConfigurationProperties`) fournit le préfixe d'API, lu **une
seule fois à l'init**, normalisé, puis utilisé comme **source unique** pour construire
tous les patterns. Si le préfixe change, tout suit — pas de chaînes en dur dispersées.

### Config HTTP générale (signature d'une API REST stateless)

- **CSRF désactivé** : la protection CSRF vise les attaques via cookies de session
  navigateur ; inutile sur une API authentifiée par token dans un header.
- **Session `STATELESS`** : pas de `HttpSession`, chaque requête se ré-authentifie via
  son token.
- **`httpBasic` / `formLogin` désactivés** : pas de login HTML ni popup Basic ;
  l'auth passe par les filtres custom.

### Règles d'autorisation — l'ordre est capital

Évaluées de haut en bas, **la première qui correspond gagne**. Les patterns spécifiques
(`/admin/**`, `/agents/**`, `/conversations/**`) sont placés **avant** le générique
`apiPattern` (`/**`) qui les engloberait sinon.

Deux points :

- `hasRole("ADMIN")` teste l'autorité **`ROLE_ADMIN`** — Spring ajoute le préfixe
  `ROLE_` automatiquement.
- Modèle métier : `SYSTEM` (API key) n'atteint **que** les conversations ;
  `STANDARD`/`ADMIN` (JWT) atteignent les agents ; seul `ADMIN` touche `/admin`. Ces
  guards ne font qu'un **premier filtrage par URL** ; l'*owner-scoping* fin
  (empêcher de lire les données d'un autre) est fait dans chaque cas d'usage via
  *existence-hiding* (404 plutôt que 403, pour ne pas révéler l'existence d'une ressource).

### Le cas `ASYNC` (SSE)

```java
.dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
```

Quand un `SseEmitter` écrit une frame, Tomcat **ré-entre dans la chaîne** avec un
dispatch `ASYNC`, sur un **thread recyclé** ne portant plus le `SecurityContext`. La
requête initiale (dispatch `REQUEST`) est déjà autorisée. Sans ce `permitAll`,
l'`AuthorizationFilter` verrait un contexte vide et tenterait un **403** — écrit sur une
réponse dont les en-têtes ont **déjà été envoyés** par l'`SseEmitter`, cassant le flux.

### Gestion des erreurs

- **`AuthenticationEntryPoint`** : utilisateur **non authentifié** → **401**.
- **`AccessDeniedHandler`** : utilisateur **authentifié mais sans droit** → **403**.

Personnalisés sur une API REST pour renvoyer du JSON propre plutôt que des pages HTML.

### Ordre des filtres — le cœur

Les `addFilterBefore` / `addFilterAfter` ancrent chaque filtre par rapport à un autre.
Ordre effectif reconstitué (du plus externe au plus interne) :

```
CorrelationIdFilter → RateLimitFilter → JwtAuthenticationFilter
  → ApiKeyAuthenticationFilter → ForcedPasswordChangeFilter → … → AuthorizationFilter
```

- **`CorrelationIdFilter` en premier** : un ID de corrélation existe *avant tout le
  reste* — même une requête rejetée en 429 (rate limit) porte l'`X-Correlation-Id`.
- **`RateLimitFilter` avant l'authentification** : le trafic **non authentifié**
  (tentatives de login, headers malformés) compte dans le quota → un *credential-stuffing*
  ne peut pas contourner le throttling.
- **Filtres d'auth** (`Jwt` puis `ApiKey`) : identifient l'appelant et peuplent le
  `SecurityContext`. Deux mécanismes essayés successivement.
- **`ForcedPasswordChangeFilter` en dernier des custom** : il faut d'abord savoir *qui*
  est l'utilisateur (donc après l'auth) pour décider s'il doit changer son mot de passe ;
  placé avant l'`AuthorizationFilter` pour bloquer l'accès tant que ce n'est pas fait.
- **`AuthorizationFilter`** (implicite, en fin) : applique les règles
  `authorizeHttpRequests`. En dernier, une fois le `SecurityContext` renseigné.

> **Détail :** `formLogin` étant désactivé, `UsernamePasswordAuthenticationFilter` n'est
> pas réellement dans la chaîne. L'utiliser comme **point d'ancrage** reste valide :
> Spring se sert de sa position enregistrée dans son registre d'ordre, présent ou non.

---

## 8. Authentification vs Autorisation

Point de compréhension central : **l'authentification n'est pas en amont de la chaîne**,
elle est réalisée **par des filtres qui en font partie**.

| | Question | Qui | Où dans la chaîne |
|---|---|---|---|
| **Authentification** | « qui es-tu ? » (établir l'identité) | `JwtAuthenticationFilter`, `ApiKeyAuthenticationFilter` | **milieu** |
| **Autorisation** | « as-tu le droit ? » (rôles / permissions) | `AuthorizationFilter` | **fin** |

En entrant dans la chaîne, une requête est **non authentifiée**. Elle le devient au fil
de sa traversée, à hauteur des filtres d'auth.

La règle `.authenticated()` relève de l'**autorisation** : elle ne fait pas
l'authentification, elle **vérifie a posteriori** que le `SecurityContext` a bien été
peuplé par un filtre situé plus haut. D'où l'ordre : l'`AuthorizationFilter` est
volontairement **en dernier** pour lire un contexte déjà rempli (ou non).

### Le fil conducteur : `SecurityContext`

Trait d'union entre les deux moments : un filtre d'auth **écrit** son contenu ;
l'`AuthorizationFilter` (et `@PreAuthorize`, et la couche métier) le **lit**. Stocké par
défaut dans un `ThreadLocal` (attaché au thread) — ce qui explique le cas `ASYNC` du
SSE : sur un thread recyclé, le `ThreadLocal` ne porte plus l'identité, d'où le
`permitAll` sur `ASYNC`.

---

## 9. Exemple : `JwtAuthenticationFilter`

Illustration concrète du basculement *anonyme → authentifié*.

```java
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenService jwtTokenService;
    private final JwtDenylist jwtDenylist;
    private final HandlerExceptionResolver resolver;

    public JwtAuthenticationFilter(
            JwtTokenService jwtTokenService,
            JwtDenylist jwtDenylist,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.jwtTokenService = jwtTokenService;
        this.jwtDenylist = jwtDenylist;
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        String rawToken = header.substring(BEARER_PREFIX.length()).trim();
        try {
            TokenClaims claims = jwtTokenService.verify(rawToken);
            if (jwtDenylist.contains(claims.jti())) {
                throw new InvalidCredentialsException();
            }
            UserPrincipal principal = new UserPrincipal(
                    claims.userId(), claims.email(), claims.role(), claims.jti(), claims.expiresAt());
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + claims.role().name())));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (InvalidCredentialsException ex) {
            SecurityContextHolder.clearContext();
            resolver.resolveException(request, response, null, ex);
            return; // resolver a écrit la réponse — on stoppe la chaîne.
        }
        chain.doFilter(request, response);
    }
}
```

### Les dépendances

- **`JwtTokenService`** : vérifie signature/validité, extrait les *claims*.
- **`JwtDenylist`** : liste de révocation → invalider un token **avant son expiration**
  (déconnexion, compromission). Sans elle, un JWT signé serait valide jusqu'à expiration
  quoi qu'il arrive (nature *stateless* du JWT).
- **`HandlerExceptionResolver`** avec **`@Qualifier("handlerExceptionResolver")`** : vise
  explicitement le résolveur global de Spring MVC (celui qui traite les
  `@ExceptionHandler` / `@ControllerAdvice`) parmi plusieurs candidats.

### Le court-circuit : pas de token = on passe

L'**absence de token n'est pas une erreur** ici. Si le header est absent ou sans
`Bearer `, le filtre passe la main sans peupler le contexte. Trois raisons :

1. certaines routes sont publiques (`/auth/login`, `/actuator/health`) ;
2. un autre filtre peut prendre le relais (`ApiKeyAuthenticationFilter` juste après) ;
3. le verdict « faut-il être authentifié ? » appartient à l'`AuthorizationFilter`.

Chaque filtre d'auth **tente** de peupler le contexte s'il le peut, et laisse la
décision finale à l'autorisation.

### Le geste central : anonyme → authentifié

Après `verify` et contrôle de la denylist (`jti` = *JWT ID*, identifiant unique du
token), on construit l'`Authentication`. Les trois arguments du
`UsernamePasswordAuthenticationToken` :

- **`principal`** : identité authentifiée (`UserPrincipal` riche : id, email, rôle, jti,
  expiration), reconstruite depuis les claims.
- **`null` (credentials)** : délibérément nul — inutile de conserver le token en mémoire
  une fois l'identité établie (bonne pratique de sécurité).
- **Autorités** : `"ROLE_" + role` — **jonction avec l'autorisation**. Ce préfixe boucle
  avec `hasRole("ADMIN")` de la chaîne (qui cherche `ROLE_ADMIN`). Si les deux ne
  s'accordaient pas, l'autorisation échouerait silencieusement.

Le constructeur à **trois arguments** marque le token comme **déjà authentifié**
(`isAuthenticated() == true`). `UsernamePasswordAuthenticationToken` est simplement une
implémentation générique et pratique de `Authentication` (aucun mot de passe en jeu ici).

`SecurityContextHolder.getContext().setAuthentication(...)` = **LE point de bascule** :
avant, la requête est anonyme ; après, le contexte porte une identité que
l'`AuthorizationFilter`, `@PreAuthorize` et la couche métier pourront lire.

### La gestion d'erreur — le passage astucieux

- **`SecurityContextHolder.clearContext()`** : ne pas laisser un contexte « à moitié
  construit ». Défensif, coût nul (même souci d'hygiène que le `finally` du MDC).
- **`resolver.resolveException(...)`** : un filtre servlet s'exécute **avant** le
  `DispatcherServlet`, donc **hors de portée** de `@ControllerAdvice` / `@ExceptionHandler`.
  Une exception levée dans un filtre échapperait à la gestion d'erreur centralisée.
  En déléguant au `handlerExceptionResolver`, on **réinjecte l'exception dans la
  machinerie MVC** : le `@ControllerAdvice` qui transforme une `InvalidCredentialsException`
  en JSON `401` propre s'applique, comme si l'exception venait d'un contrôleur.
  → **format d'erreur unique et cohérent** sur toute l'API. Le `null` en 3ᵉ argument est
  le `handler` (méthode de contrôleur visée), inconnu ici.
- **`return`** : la réponse ayant été écrite par le resolver, ne surtout pas appeler
  `chain.doFilter` sur une réponse déjà rédigée.

### Deux chemins vers un 401

- Ce filtre : token **présent mais invalide/révoqué** → 401 riche via le resolver.
- L'`authenticationEntryPoint` de la chaîne : **aucune** authentification sur route
  protégée → la requête traverse tous les filtres sans peupler le contexte,
  l'`AuthorizationFilter` la refuse.

---

## Récapitulatif des fils conducteurs

- **Injection par paramètre `@Bean`** : vue avec `@Configuration`, réutilisée pour
  `HttpSecurity` et pour tous les filtres custom de la chaîne.
- **`@ConfigurationProperties`** : le bean `properties` qui fournit la « source unique »
  du préfixe d'API.
- **Hygiène du contexte lié au thread** : `finally` du MDC (`CorrelationIdFilter`) ↔
  `clearContext()` (`JwtAuthenticationFilter`) ↔ cas `ASYNC` du SSE.
- **Le préfixe `ROLE_`** : produit par le filtre JWT, consommé par `hasRole(...)` de la
  chaîne et par `@PreAuthorize`.
- **`SecurityContext`** : écrit par les filtres d'auth (milieu), lu par
  l'`AuthorizationFilter` (fin) et la sécurité par méthode.
- **Stateless mais révocable** : JWT + `JwtDenylist`.
- **Erreurs hors `DispatcherServlet`** : résolues par délégation au
  `HandlerExceptionResolver`.

## 10. `@AuthenticationPrincipal`

Le pont entre la sécurité et la logique métier : récupérer, côté contrôleur, le
`UserPrincipal` que le filtre a déposé dans le `SecurityContext`.

### Le rôle

Placée sur un paramètre de méthode de contrôleur, elle injecte le **principal** de
l'authentification courante :

```java
@GetMapping("/me")
public UserDto me(@AuthenticationPrincipal UserPrincipal principal) {
    return userService.toDto(principal.userId());
}
```

Spring récupère l'`Authentication` du `SecurityContext`, en extrait le principal
(`authentication.getPrincipal()`), et le passe en paramètre — déjà typé.

### Le lien avec le filtre JWT

Rappel de `JwtAuthenticationFilter` :

```java
UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
        principal,   // <-- ce UserPrincipal
        null,
        List.of(...));
SecurityContextHolder.getContext().setAuthentication(authentication);
```

Le `principal` mis en **premier argument** est exactement celui que
`@AuthenticationPrincipal` rend. Aller-retour : le filtre l'**écrit**, le contrôleur le
**lit** — même objet, sans reconstruction ni relecture du token.

### Ce que ça remplace

Sans l'annotation, code verbeux et non typé :

```java
@GetMapping("/me")
public UserDto me() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    UserPrincipal principal = (UserPrincipal) auth.getPrincipal();  // cast manuel
    return userService.toDto(principal.userId());
}
```

L'annotation supprime l'accès statique au `SecurityContextHolder`, le cast et la
vérification de nullité. Le contrôleur devient **testable** (passer un `UserPrincipal`
en argument dans un test unitaire, sans monter de contexte de sécurité) et la dépendance
à l'identité devient **explicite dans la signature**.

### Points subtils

- **Le type déclaré doit correspondre au principal réel.** Ici ça marche car le filtre
  met un `UserPrincipal`. Type incompatible → Spring injecte `null` (pas de cast forcé).
  D'où l'importance du choix de `UserPrincipal` dans le constructeur du token.
- **Sur une route non authentifiée, le principal peut être `null`.** Sur les routes
  protégées ça n'arrive pas (l'`AuthorizationFilter` a déjà exigé `.authenticated()` en
  amont). Mais sur une route `permitAll`, prévoir le cas `null`.

### Option SpEL : extraire un champ directement

```java
public void action(@AuthenticationPrincipal(expression = "userId") UUID userId) { ... }
```

Pratique pour un seul champ ; injecter le `UserPrincipal` entier reste souvent plus
lisible.

### Variante : annotation méta-personnalisée

Comme `@AuthenticationPrincipal UserPrincipal principal` se répète partout, on crée
souvent un raccourci :

```java
@Target({ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@AuthenticationPrincipal
public @interface CurrentUser {}
```

puis `public UserDto me(@CurrentUser UserPrincipal principal)`. Purement cosmétique,
mais documente l'intention et centralise l'annotation.

### En résumé

`@AuthenticationPrincipal UserPrincipal principal` injecte dans le contrôleur l'identité
authentifiée déposée par le filtre JWT dans le `SecurityContext`, en évitant l'accès
manuel au `SecurityContextHolder` et le cast. Aboutissement de la chaîne : le filtre
établit *qui* agit, l'annotation le remet entre les mains du code applicatif.