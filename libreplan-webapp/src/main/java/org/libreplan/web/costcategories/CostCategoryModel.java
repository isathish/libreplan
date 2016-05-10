/*
 * This file is part of LibrePlan
 *
 * Copyright (C) 2009-2010 Fundación para o Fomento da Calidade Industrial e
 *                         Desenvolvemento Tecnolóxico de Galicia
 * Copyright (C) 2010-2011 Igalia, S.L.
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

package org.libreplan.web.costcategories;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.Validate;
import org.libreplan.business.common.IntegrationEntity;
import org.libreplan.business.common.daos.IConfigurationDAO;
import org.libreplan.business.common.entities.EntityNameEnum;
import org.libreplan.business.common.exceptions.InstanceNotFoundException;
import org.libreplan.business.common.exceptions.ValidationException;
import org.libreplan.business.costcategories.daos.ICostCategoryDAO;
import org.libreplan.business.costcategories.daos.IResourcesCostCategoryAssignmentDAO;
import org.libreplan.business.costcategories.daos.ITypeOfWorkHoursDAO;
import org.libreplan.business.costcategories.entities.CostCategory;
import org.libreplan.business.costcategories.entities.HourCost;
import org.libreplan.business.costcategories.entities.TypeOfWorkHours;
import org.libreplan.business.resources.daos.ICriterionDAO;
import org.libreplan.web.common.IntegrationEntityModel;
import org.libreplan.web.common.concurrentdetection.OnConcurrentModification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Model for UI operations related to {@link CostCategory}
 *
 * @author Jacobo Aragunde Perez <jaragunde@igalia.com>
 * @author Diego Pino García <dpino@igalia.com>
 */
@Service
@Scope(BeanDefinition.SCOPE_PROTOTYPE)
@OnConcurrentModification(goToPage = "/costcategories/costCategory.zul")
public class CostCategoryModel extends IntegrationEntityModel implements
        ICostCategoryModel {

    private CostCategory costCategory;

    @Autowired
    private ICostCategoryDAO costCategoryDAO;

    @Autowired
    private ICriterionDAO criterionDAO;

    @Autowired
    private IResourcesCostCategoryAssignmentDAO resourcesCostCategoryAssignmentDAO;

    @Autowired
    private IConfigurationDAO configurationDAO;

    @Autowired
    private ITypeOfWorkHoursDAO typeOfWorkHoursDAO;

    @Override
    public List<CostCategory> getCostCategories() {
        return costCategoryDAO.list(CostCategory.class);
    }

    @Override
    @Transactional(readOnly = true)
    public void initCreate() {
        boolean codeAutogenerated = configurationDAO.getConfiguration()
                .getGenerateCodeForCostCategory();
        costCategory = CostCategory.create();
        costCategory.setCode("");
        if (codeAutogenerated) {
            this.setDefaultCode();
        }
        costCategory.setCodeAutogenerated(codeAutogenerated);
    }

    @Override
    @Transactional(readOnly = true)
    public void initEdit(CostCategory costCategory) {
        Validate.notNull(costCategory);
        this.costCategory = getFromDB(costCategory);
        this.initOldCodes();
    }

    @Transactional(readOnly = true)
    private CostCategory getFromDB(CostCategory costCategory) {
        return getFromDB(costCategory.getId());
    }

    @Transactional(readOnly = true)
    private CostCategory getFromDB(Long id) {
        try {
            CostCategory result = costCategoryDAO.find(id);
            forceLoadEntities(result);
            return result;
        } catch (InstanceNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Load entities that will be needed in the conversation
     *
     * @param costCategory
     */
    private void forceLoadEntities(CostCategory costCategory) {
        for (HourCost each : costCategory.getHourCosts()) {
            each.getInitDate();
            each.getCategory().getName();
            each.getType().getName();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Set<HourCost> getHourCosts() {
        Set<HourCost> hourCosts = new HashSet<HourCost>();
        if (costCategory != null) {
            hourCosts.addAll(costCategory.getHourCosts());
        }
        return hourCosts;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TypeOfWorkHours> getAllHoursType() {
        return typeOfWorkHoursDAO.hoursTypeByNameAsc();
    }

    @Override
    public CostCategory getCostCategory() {
        return costCategory;
    }

    @Override
    @Transactional
    public void confirmSave() throws ValidationException {
        costCategory.generateHourCostCodes(getNumberOfDigitsCode());
        costCategoryDAO.save(costCategory);
    }

    @Override
    public void addHourCost() {
        HourCost hourCost = HourCost.create();
        hourCost.setCode("");
        costCategory.addHourCost(hourCost);

    }

    @Override
    public void removeHourCost(HourCost hourCost) {
        costCategory.removeHourCost(hourCost);
    }

    @Override
    @Transactional
    public void confirmRemoveCostCategory(CostCategory category)
            throws InstanceNotFoundException {
        costCategoryDAO.remove(category.getId());
    }

    @Override
    @Transactional(readOnly=true)
    public boolean canRemoveCostCategory(CostCategory category) {
        return (resourcesCostCategoryAssignmentDAO
                .getResourcesCostCategoryAssignmentsByCostCategory(category)
                .size() == 0)
                && !criterionDAO.hasCostCategoryAssignments(category);
    }

    public EntityNameEnum getEntityName() {
        return EntityNameEnum.COST_CATEGORY;
    }

    public Set<IntegrationEntity> getChildren() {
        return (Set<IntegrationEntity>) (costCategory != null ? costCategory
                .getHourCosts() : new HashSet<IntegrationEntity>());
    }

    public IntegrationEntity getCurrentEntity() {
        return this.costCategory;
    }

    @Override
    public void validateHourCostsOverlap() throws ValidationException {
        CostCategory.validateHourCostsOverlap(getCostCategory().getHourCosts());
    }
}