package com.cafeerp.demo;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.FluentQuery;

import jakarta.persistence.EntityNotFoundException;

/**
 * Skeletal {@link JpaRepository} backed by a plain {@link Map}.
 * <p>
 * Concrete demo repositories extend this and add only their custom query
 * methods (the {@code findBy…} / {@code @Query} methods declared on the
 * production repository interfaces), implemented as in-memory filters over
 * the session's maps. All standard CRUD, paging and sorting behavior lives
 * here, so production services observe the same contract they get from the
 * JPA implementations.
 *
 * @param <T>  entity type
 * @param <ID> id type (must be {@link Comparable} for deterministic ordering)
 */
public abstract class InMemoryJpaRepository<T, ID> implements JpaRepository<T, ID> {

    /** The session's live map for this entity (never a shared collection). */
    protected abstract Map<ID, T> store();

    protected abstract ID idOf(T entity);

    protected abstract void setId(T entity, ID id);

    /** Next id for newly saved entities (sequences live in the session store). */
    protected abstract ID nextId();

    // ---- CRUD ----

    @Override
    public Optional<T> findById(ID id) {
        return Optional.ofNullable(store().get(id));
    }

    @Override
    public boolean existsById(ID id) {
        return store().containsKey(id);
    }

    @Override
    public long count() {
        return store().size();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <S extends T> S save(S entity) {
        ID id = (ID) idOf(entity);
        if (id == null) {
            id = nextId();
            setId(entity, id);
        }
        store().put(id, entity);
        afterSave(entity);
        return entity;
    }

    /**
     * Hook for cascade-like bookkeeping (e.g. orders keep their line items
     * in the item map, mirroring JPA's {@code CascadeType.ALL}).
     */
    protected void afterSave(T entity) {
    }

    @Override
    public <S extends T> List<S> saveAll(Iterable<S> entities) {
        List<S> saved = new ArrayList<>();
        for (S entity : entities) {
            saved.add(save(entity));
        }
        return saved;
    }

    @Override
    public List<T> findAll() {
        List<T> all = new ArrayList<>(store().values());
        all.sort(idComparator());
        return all;
    }

    @Override
    public List<T> findAllById(Iterable<ID> ids) {
        List<T> found = new ArrayList<>();
        for (ID id : ids) {
            T entity = store().get(id);
            if (entity != null) {
                found.add(entity);
            }
        }
        return found;
    }

    @Override
    public List<T> findAll(Sort sort) {
        List<T> all = new ArrayList<>(store().values());
        all.sort(comparatorFor(sort));
        return all;
    }

    @Override
    public Page<T> findAll(Pageable pageable) {
        List<T> all = findAll(pageable.getSort());
        return pageOf(all, pageable);
    }

    @Override
    public void deleteById(ID id) {
        T entity = store().remove(id);
        if (entity != null) {
            afterDelete(entity);
        }
    }

    @Override
    public void delete(T entity) {
        if (entity != null && idOf(entity) != null) {
            deleteById(idOf(entity));
        }
    }

    @Override
    public void deleteAllById(Iterable<? extends ID> ids) {
        for (ID id : ids) {
            deleteById(id);
        }
    }

    @Override
    public void deleteAll(Iterable<? extends T> entities) {
        for (T entity : entities) {
            delete(entity);
        }
    }

    @Override
    public void deleteAll() {
        List<T> all = new ArrayList<>(store().values());
        store().clear();
        for (T entity : all) {
            afterDelete(entity);
        }
    }

    /** Hook for cascade-like cleanup on delete (mirrors orphan removal). */
    protected void afterDelete(T entity) {
    }

    @Override
    public T getReferenceById(ID id) {
        T entity = store().get(id);
        if (entity == null) {
            throw new EntityNotFoundException("No entity with id " + id);
        }
        return entity;
    }

    @Override
    @Deprecated
    public T getOne(ID id) {
        return getReferenceById(id);
    }

    @Override
    @Deprecated
    public T getById(ID id) {
        return getReferenceById(id);
    }

    // ---- JPA specifics (no-ops in memory) ----

    @Override
    public void flush() {
    }

    @Override
    public <S extends T> S saveAndFlush(S entity) {
        return save(entity);
    }

    @Override
    public <S extends T> List<S> saveAllAndFlush(Iterable<S> entities) {
        return saveAll(entities);
    }

    @Override
    public void deleteAllInBatch(Iterable<T> entities) {
        deleteAll(entities);
    }

    @Override
    public void deleteAllByIdInBatch(Iterable<ID> ids) {
        deleteAllById(ids);
    }

    @Override
    public void deleteAllInBatch() {
        deleteAll();
    }

    // ---- Query-by-example (minimal probe matching; unused by services) ----

    @Override
    public <S extends T> Optional<S> findOne(Example<S> example) {
        return findAll(example).stream().findFirst();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <S extends T> List<S> findAll(Example<S> example) {
        List<S> matched = new ArrayList<>();
        for (T entity : store().values()) {
            if (matches(example, (S) entity)) {
                matched.add((S) entity);
            }
        }
        return matched;
    }

    @Override
    public <S extends T> List<S> findAll(Example<S> example, Sort sort) {
        List<S> matched = findAll(example);
        matched.sort((Comparator<? super S>) comparatorFor(sort));
        return matched;
    }

    @Override
    public <S extends T> Page<S> findAll(Example<S> example, Pageable pageable) {
        return pageOf(findAll(example, pageable.getSort()), pageable);
    }

    @Override
    public <S extends T> long count(Example<S> example) {
        return findAll(example).size();
    }

    @Override
    public <S extends T> boolean exists(Example<S> example) {
        return findOne(example).isPresent();
    }

    @Override
    public <S extends T, R> R findBy(Example<S> example,
                                     Function<FluentQuery.FetchableFluentQuery<S>, R> queryFunction) {
        throw new UnsupportedOperationException("Fluent query-by-example is not supported in demo mode");
    }

    // ---- Helpers ----

    protected static <X> Page<X> pageOf(List<X> all, Pageable pageable) {
        int total = all.size();
        int offset = (int) Math.min(pageable.getOffset(), total);
        int end = Math.min(offset + pageable.getPageSize(), total);
        return new PageImpl<>(all.subList(offset, end), pageable, total);
    }

    private Comparator<T> idComparator() {
        return (a, b) -> compareValues(idOf(a), idOf(b));
    }

    protected Comparator<T> comparatorFor(Sort sort) {
        if (sort == null || sort.isUnsorted()) {
            return idComparator();
        }
        Comparator<T> result = null;
        for (Sort.Order order : sort) {
            Comparator<Comparable<Object>> valueOrder =
                    Comparator.<Comparable<Object>>nullsFirst(Comparator.<Comparable<Object>>naturalOrder());
            Comparator<T> next = Comparator.comparing(
                    entity -> (Comparable<Object>) propertyOf(entity, order.getProperty()),
                    valueOrder);
            if (order.isDescending()) {
                next = next.reversed();
            }
            result = result == null ? next : result.thenComparing(next);
        }
        return result == null ? idComparator() : result;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static int compareValues(Object a, Object b) {
        if (a == b) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        if (a instanceof Comparable comparable) {
            return comparable.compareTo(b);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    /** Reads a bean property via its getter ({@code getXxx}/{@code isXxx}). */
    protected static Object propertyOf(Object bean, String property) {
        String base = property.substring(0, 1).toUpperCase() + property.substring(1);
        for (String prefix : new String[] { "get", "is" }) {
            try {
                Method getter = bean.getClass().getMethod(prefix + base);
                return getter.invoke(bean);
            } catch (NoSuchMethodException e) {
                // try the next prefix
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("Cannot read property '" + property + "'", e);
            }
        }
        // Fall back to direct field access (e.g. record-style or odd names).
        try {
            java.lang.reflect.Field field = bean.getClass().getDeclaredField(property);
            field.setAccessible(true);
            return field.get(bean);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("No readable property '" + property + "'", e);
        }
    }

    private static <S> boolean matches(Example<S> example, S entity) {
        S probe = example.getProbe();
        for (java.lang.reflect.Field field : probe.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    || field.isSynthetic()) {
                continue;
            }
            field.setAccessible(true);
            try {
                Object wanted = field.get(probe);
                if (wanted == null) {
                    continue;
                }
                Object actual = field.get(entity);
                if (!wanted.equals(actual)) {
                    return false;
                }
            } catch (IllegalAccessException e) {
                return false;
            }
        }
        return true;
    }
}
