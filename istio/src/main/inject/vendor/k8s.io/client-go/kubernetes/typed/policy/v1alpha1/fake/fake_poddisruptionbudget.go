/*
Copyright 2016 The Kubernetes Authors.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package fake

import (
	api "k8s.io/client-go/pkg/api"
	unversioned "k8s.io/client-go/pkg/api/unversioned"
	v1 "k8s.io/client-go/pkg/api/v1"
	v1alpha1 "k8s.io/client-go/pkg/apis/policy/v1alpha1"
	labels "k8s.io/client-go/pkg/labels"
	watch "k8s.io/client-go/pkg/watch"
	testing "k8s.io/client-go/testing"
)

// FakePodDisruptionBudgets implements PodDisruptionBudgetInterface
type FakePodDisruptionBudgets struct {
	Fake *FakePolicyV1alpha1
	ns   string
}

var poddisruptionbudgetsResource = unversioned.GroupVersionResource{Group: "policy", Version: "v1alpha1", Resource: "poddisruptionbudgets"}

func (c *FakePodDisruptionBudgets) Create(podDisruptionBudget *v1alpha1.PodDisruptionBudget) (result *v1alpha1.PodDisruptionBudget, err error) {
	obj, err := c.Fake.
		Invokes(testing.NewCreateAction(poddisruptionbudgetsResource, c.ns, podDisruptionBudget), &v1alpha1.PodDisruptionBudget{})

	if obj == nil {
		return nil, err
	}
	return obj.(*v1alpha1.PodDisruptionBudget), err
}

func (c *FakePodDisruptionBudgets) Update(podDisruptionBudget *v1alpha1.PodDisruptionBudget) (result *v1alpha1.PodDisruptionBudget, err error) {
	obj, err := c.Fake.
		Invokes(testing.NewUpdateAction(poddisruptionbudgetsResource, c.ns, podDisruptionBudget), &v1alpha1.PodDisruptionBudget{})

	if obj == nil {
		return nil, err
	}
	return obj.(*v1alpha1.PodDisruptionBudget), err
}

func (c *FakePodDisruptionBudgets) UpdateStatus(podDisruptionBudget *v1alpha1.PodDisruptionBudget) (*v1alpha1.PodDisruptionBudget, error) {
	obj, err := c.Fake.
		Invokes(testing.NewUpdateSubresourceAction(poddisruptionbudgetsResource, "status", c.ns, podDisruptionBudget), &v1alpha1.PodDisruptionBudget{})

	if obj == nil {
		return nil, err
	}
	return obj.(*v1alpha1.PodDisruptionBudget), err
}

func (c *FakePodDisruptionBudgets) Delete(name string, options *v1.DeleteOptions) error {
	_, err := c.Fake.
		Invokes(testing.NewDeleteAction(poddisruptionbudgetsResource, c.ns, name), &v1alpha1.PodDisruptionBudget{})

	return err
}

func (c *FakePodDisruptionBudgets) DeleteCollection(options *v1.DeleteOptions, listOptions v1.ListOptions) error {
	action := testing.NewDeleteCollectionAction(poddisruptionbudgetsResource, c.ns, listOptions)

	_, err := c.Fake.Invokes(action, &v1alpha1.PodDisruptionBudgetList{})
	return err
}

func (c *FakePodDisruptionBudgets) Get(name string) (result *v1alpha1.PodDisruptionBudget, err error) {
	obj, err := c.Fake.
		Invokes(testing.NewGetAction(poddisruptionbudgetsResource, c.ns, name), &v1alpha1.PodDisruptionBudget{})

	if obj == nil {
		return nil, err
	}
	return obj.(*v1alpha1.PodDisruptionBudget), err
}

func (c *FakePodDisruptionBudgets) List(opts v1.ListOptions) (result *v1alpha1.PodDisruptionBudgetList, err error) {
	obj, err := c.Fake.
		Invokes(testing.NewListAction(poddisruptionbudgetsResource, c.ns, opts), &v1alpha1.PodDisruptionBudgetList{})

	if obj == nil {
		return nil, err
	}

	label, _, _ := testing.ExtractFromListOptions(opts)
	if label == nil {
		label = labels.Everything()
	}
	list := &v1alpha1.PodDisruptionBudgetList{}
	for _, item := range obj.(*v1alpha1.PodDisruptionBudgetList).Items {
		if label.Matches(labels.Set(item.Labels)) {
			list.Items = append(list.Items, item)
		}
	}
	return list, err
}

// Watch returns a watch.Interface that watches the requested podDisruptionBudgets.
func (c *FakePodDisruptionBudgets) Watch(opts v1.ListOptions) (watch.Interface, error) {
	return c.Fake.
		InvokesWatch(testing.NewWatchAction(poddisruptionbudgetsResource, c.ns, opts))

}

// Patch applies the patch and returns the patched podDisruptionBudget.
func (c *FakePodDisruptionBudgets) Patch(name string, pt api.PatchType, data []byte, subresources ...string) (result *v1alpha1.PodDisruptionBudget, err error) {
	obj, err := c.Fake.
		Invokes(testing.NewPatchSubresourceAction(poddisruptionbudgetsResource, c.ns, name, data, subresources...), &v1alpha1.PodDisruptionBudget{})

	if obj == nil {
		return nil, err
	}
	return obj.(*v1alpha1.PodDisruptionBudget), err
}
