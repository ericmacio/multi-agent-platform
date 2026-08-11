# ToolCatalogAdapter — explication

Registre de « tools » (outils) pour une application Spring, dans un contexte
multi-agents. Au démarrage, la classe découvre automatiquement tous les outils
déclarés dans l'application, les valide, puis expose une liste figée que le
reste du programme peut consulter.

## Rôle et cycle de vie

- Annotée `@Component` : Spring l'instancie et l'ajoute à son contexte.
- Implémente l'interface `ToolCatalog` (implémentation concrète / « adapter »
  au sens de l'architecture hexagonale).
- Reçoit l'`ApplicationContext` par son constructeur, donnant accès à
  l'ensemble des beans gérés par Spring.
- Le cœur de la logique est `scan()`, marquée `@PostConstruct` : elle s'exécute
  **une seule fois**, juste après l'injection des dépendances et avant toute
  utilisation du bean. Le catalogue est ainsi construit une bonne fois pour
  toutes.

## Ce que fait `scan()`

1. **Découverte** — `applicationContext.getBeansWithAnnotation(ToolGroup.class)`
   récupère tous les beans portant l'annotation `@ToolGroup`. N'importe quelle
   classe annotée est trouvée sans enregistrement manuel.

2. **Relecture de l'annotation** — pour chaque bean, `AnnotationUtils.findAnnotation(...)`
   relit l'annotation. Nécessaire car Spring crée parfois des **proxies CGLIB**
   qui ne portent pas l'annotation sur la classe proxy elle-même ;
   `findAnnotation` sait remonter la hiérarchie. Le `if (group == null) continue;`
   est une précaution défensive.

3. **Validation** — la construction de `ToolDescriptor(group.name(), group.description())`
   applique les règles structurelles (nom ≤ 64 caractères, champs non vides)
   dans le constructeur du descripteur. Une valeur invalide fait **échouer
   l'application au démarrage**, pas en pleine conversation.

## Détection des doublons (fail-fast)

Une `Map<String, Class<?>> firstDeclarer` enregistre, pour chaque nom d'outil,
la classe qui l'a déclaré en premier. `putIfAbsent` renvoie la valeur déjà
présente si le nom existe déjà :

```java
Class<?> existing = firstDeclarer.putIfAbsent(descriptor.name(), beanClass);
if (existing != null) {
    throw new IllegalStateException("Duplicate tool catalog entry '" + ... );
}
```

Pourquoi une map séparée alors que `collected` connaît déjà les noms ? Pour le
**message d'erreur** : `collected` stocke des descripteurs, pas les classes.
`firstDeclarer` permet de nommer *les deux* classes fautives dans l'exception.
Choix explicite : pas d'écrasement silencieux, pas de surprise à l'usage.

## Finalisation et immuabilité

Après la boucle, trois structures sont figées (collections non modifiables) :

| Champ         | Contenu                    | Utilisé par      |
|---------------|----------------------------|------------------|
| `snapshot`    | liste des descripteurs triée par nom | `all()`   |
| `byName`      | map nom → descripteur      | `contains()`     |
| `beanByName`  | map nom → instance du bean | `resolveBean()`  |

Une fois `scan()` terminé, le catalogue ne change plus pour toute la durée de
vie de la JVM.

## Méthodes publiques

Triviales par construction — tout le travail coûteux a eu lieu au démarrage :

- `all()` : renvoie la liste triée.
- `contains(name)` : simple lookup dans `byName`.
- `resolveBean(name)` : renvoie un `Optional` contenant le bean si le nom
  existe (permettant d'invoquer réellement l'outil).

## Points forts du design

Séparation claire de trois préoccupations :

- **Découverte** — parcours du contexte Spring.
- **Validation** — déléguée au constructeur de `ToolDescriptor`.
- **Exposition** — les trois méthodes de lecture.

## Point de vigilance (concurrence)

Les champs `snapshot`, `byName` et `beanByName` ne sont ni `final` ni
`volatile`. En pratique, le cycle de vie de Spring garantit que `scan()`
termine avant toute utilisation du bean, donc les lectures ultérieures sont
sûres. Pour rendre la publication mémoire explicite et à toute épreuve, on
pourrait les déclarer `volatile`. Ce n'est pas un bug dans le contexte Spring
habituel, juste une précision pour une revue de code.