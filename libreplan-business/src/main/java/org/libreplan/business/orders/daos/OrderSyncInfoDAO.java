/*
 * This file is part of LibrePlan
 *
 * Copyright (C) 2013 St. Antoniusziekenhuis
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.libreplan.business.orders.daos;

import java.util.List;

import org.hibernate.Criteria;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Projections;
import org.hibernate.criterion.Property;
import org.hibernate.criterion.Restrictions;
import org.libreplan.business.common.daos.GenericDAOHibernate;
import org.libreplan.business.orders.entities.Order;
import org.libreplan.business.orders.entities.OrderSyncInfo;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Repository;

/**
 * DAO for {@link OrderSyncInfo}
 *
 * @author Miciele Ghiorghis <m.ghiorghis@antoniusziekenhuis.nl>
 */
@Repository
@Scope(BeanDefinition.SCOPE_SINGLETON)
public class OrderSyncInfoDAO extends GenericDAOHibernate<OrderSyncInfo, Long>
        implements IOrderSyncInfoDAO {

    @Override
    public OrderSyncInfo findByOrderLastSynchronizedInfo(Order order) {
        DetachedCriteria mostRecentDate = DetachedCriteria
                .forClass(OrderSyncInfo.class)
                .setProjection(Projections.max("lastSyncDate"))
                .add(Restrictions.isNotNull("code"));

        Criteria criteria = getSession().createCriteria(OrderSyncInfo.class);

        criteria.add(Restrictions.eq("order", order));
        criteria.add(Property.forName("lastSyncDate").eq(mostRecentDate));

        return (OrderSyncInfo) criteria.uniqueResult();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<OrderSyncInfo> findByOrderLastSynchronizedInfos(Order order) {
        Criteria criteria = getSession().createCriteria(OrderSyncInfo.class);
        criteria.add(Restrictions.eq("order", order));
        criteria.add(Restrictions.isNotNull("code"));
        criteria.addOrder(org.hibernate.criterion.Order.desc("lastSyncDate"));
        return criteria.list();
    }

}