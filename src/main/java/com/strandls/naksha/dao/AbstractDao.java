package com.strandls.naksha.dao;

import java.io.Serializable;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.List;

import javax.persistence.NoResultException;

import org.hibernate.Criteria;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.criterion.CriteriaSpecification;

public abstract class AbstractDao<T, K extends Serializable> {

	protected SessionFactory sessionFactory;

	protected Class<? extends T> daoType;

	protected AbstractDao(SessionFactory sessionFactory) {
		daoType = (Class<T>) ((ParameterizedType) getClass().getGenericSuperclass()).getActualTypeArguments()[0];
		this.sessionFactory = sessionFactory;
	}

	public T save(T entity) {
		Session session = sessionFactory.openSession();
		Transaction tx = null;
		try {
			tx = session.beginTransaction();
			session.save(entity);
			tx.commit();
		} catch (Exception e) {
			if (tx != null)
				tx.rollback();
			throw e;
		} finally {
			session.close();
		}
		return entity;
	}

	public T update(T entity) {
		Session session = sessionFactory.openSession();
		Transaction tx = null;
		try {
			tx = session.beginTransaction();
			session.update(entity);
			tx.commit();
		} catch (Exception e) {
			if (tx != null)
				tx.rollback();
			throw e;
		} finally {
			session.close();
		}
		return entity;
	}

	public T delete(T entity) {
		Session session = sessionFactory.openSession();
		Transaction tx = null;
		try {
			tx = session.beginTransaction();
			session.delete(entity);
			tx.commit();
		} catch (Exception e) {
			if (tx != null)
				tx.rollback();
			throw e;
		} finally {
			session.close();
		}
		return entity;
	}

	public abstract T findById(K id);

	@SuppressWarnings("unchecked")
	public List<T> findAll() {
		Session session = sessionFactory.openSession();
		List<T> entities = null;
		try {
			Criteria criteria = session.createCriteria(daoType);
			entities = criteria.setResultTransformer(CriteriaSpecification.DISTINCT_ROOT_ENTITY).list();
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			session.close();
		}
		return entities;
	}

	@SuppressWarnings("unchecked")
	public List<T> findAll(int limit, int offset) {
		
		Session session = sessionFactory.openSession();
		List<T> entities = null;
		try {
			Criteria criteria = session.createCriteria(daoType).setResultTransformer(CriteriaSpecification.DISTINCT_ROOT_ENTITY);
			return criteria.setFirstResult(offset).setMaxResults(limit).list();
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			session.close();
		}
		return entities;
	}

	public T findByPropertyWithCondition(String property, String value, String condition) {
		String queryStr = "" + "from " + daoType.getSimpleName() + " t " + "where t." + property + " " + condition
				+ " :value";
		Session session = sessionFactory.openSession();
		org.hibernate.query.Query<T> query = session.createQuery(queryStr);
		query.setParameter("value", value);

		T entity = null;
		try {
			entity = query.getSingleResult();
		} catch (NoResultException e) {
			e.printStackTrace();
			throw e;
		} finally {
			session.close();
		}
		return entity;

	}

	public List<T> getByPropertyWithCondtion(String property, Object value, String condition, int limit, int offset) {
		String queryStr = "" + "from " + daoType.getSimpleName() + " t " + "where t." + property + " " + condition
				+ " :value" + " order by id";
		Session session = sessionFactory.openSession();
		org.hibernate.query.Query<T> query = session.createQuery(queryStr);
		query.setParameter("value", value);

		List<T> resultList = new ArrayList<>();
		try {
			if (limit > 0 && offset >= 0)
				query = query.setFirstResult(offset).setMaxResults(limit);
			resultList = query.getResultList();

		} catch (NoResultException e) {
			e.printStackTrace();
			throw e;
		} finally {
			session.close();
		}
		return resultList;
	}
}
